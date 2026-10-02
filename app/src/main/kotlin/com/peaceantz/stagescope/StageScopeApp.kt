package com.peaceantz.stagescope

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.peaceantz.stagescope.assistant.AssistantRepository
import com.peaceantz.stagescope.assistant.DataLayerPhoneLink
import com.peaceantz.stagescope.assistant.PhoneHandoff
import com.peaceantz.stagescope.assistant.PhonePlaybackBridge
import com.peaceantz.stagescope.assistant.WatchIssueRepository
import com.peaceantz.stagescope.assistant.measure.MeasurementHub
import com.peaceantz.stagescope.assistant.speech.SpeechInput
import com.peaceantz.stagescope.assistant.speech.SpeechOutput
import com.peaceantz.stagescope.assistant.speech.VoiceRecorder
import com.peaceantz.stagescope.audio.AudioCaptureEngine
import com.peaceantz.stagescope.audio.AudioCoordinator
import com.peaceantz.stagescope.audio.AudioMode
import com.peaceantz.stagescope.audio.PcmSource
import com.peaceantz.stagescope.data.RingBankRepository
import com.peaceantz.stagescope.data.SettingsRepository
import com.peaceantz.stagescope.data.SnapshotRepository
import com.peaceantz.stagescope.data.SurfaceSummaryRepository
import com.peaceantz.stagescope.demo.DemoSignalGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Manual constructor-injection container -- deliberately no DI framework for a project this small. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settingsRepository = SettingsRepository(appContext)
    val snapshotRepository = SnapshotRepository(appContext)
    val surfaceSummaryRepository = SurfaceSummaryRepository(appContext)
    val ringBankRepository = RingBankRepository(appContext)
    val audioCaptureEngine = AudioCaptureEngine(appContext)
    val demoSignalGenerator = DemoSignalGenerator()

    /** Main-thread scope for app-level plumbing (audio leases, polling). Not tied to any screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Latest Analyzer/Ring state, read only when the person asks the assistant a question. */
    val measurementHub = MeasurementHub(SystemClock::elapsedRealtime)

    /**
     * Who owns the microphone and the room's acoustics: measurement, the assistant listening, the
     * watch speaking, or the phone speaking. Measurement resumes after a voice interaction only if it
     * was running, the app is in the foreground, the permission is still granted and the keep-awake
     * countdown allows it -- see [AudioCoordinator].
     */
    fun isAppForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    val audioCoordinator = AudioCoordinator(
        nowMs = SystemClock::elapsedRealtime,
        isForeground = ::isAppForeground,
        hasMicPermission = {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
    )

    // ---- AI assistant (phone-backed). Everything below is lazy: a watch used only as an instrument never builds any of it.

    private val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    val phoneLink = DataLayerPhoneLink(appContext) { assistant.installId }
    private val playbackBridge = PhonePlaybackBridge(audioCoordinator, appScope)

    val assistant: AssistantRepository by lazy {
        AssistantRepository(
            dir = File(appContext.filesDir, "assistant").apply { mkdirs() },
            link = phoneLink,
            appVersionName = packageInfo.versionName ?: "0",
            appVersionCode = packageInfo.longVersionCode,
            onPlayback = playbackBridge::onNotice,
            onAnswerReady = ::vibrateIfEnabled,
        )
    }
    val issues: WatchIssueRepository by lazy { WatchIssueRepository(File(File(appContext.filesDir, "assistant").apply { mkdirs() }, "issues.json"), phoneLink) }
    val speechInput: SpeechInput by lazy { SpeechInput(appContext) }
    val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(appContext) }
    val speechOutput: SpeechOutput by lazy { SpeechOutput(appContext, audioCoordinator, appScope) }
    val phoneHandoff: PhoneHandoff by lazy { PhoneHandoff(appContext) }

    /** One short buzz when an answer arrives -- only if the person turned vibration on (it is off by default). */
    private fun vibrateIfEnabled() {
        if (!assistant.prefs.value.hapticsEnabled) return
        val vibrator = appContext.getSystemService(Vibrator::class.java) ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    /** Chooses the live source based on the current demo-mode setting, read once at Start time. */
    fun pcmSourceFor(demoModeEnabled: Boolean): PcmSource =
        if (demoModeEnabled) demoSignalGenerator else audioCaptureEngine

    /**
     * Light background work, all of it soft-failing and none of it touching a microphone:
     *  - once at startup, load whatever the phone last published (views + its issue replica);
     *  - while the app is on screen, keep reachability fresh and deliver/flush what is due;
     *  - while anything holds the audio, expire time-limited leases.
     */
    fun startBackgroundWork() {
        appScope.launch(Dispatchers.Default) {
            runCatching {
                val items = phoneLink.currentDataItems()
                assistant.handleDataItems(items)
                issues.mergeFromPhone(items)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var poller: Job? = null

            override fun onStart(owner: LifecycleOwner) {
                poller?.cancel()
                poller = appScope.launch(Dispatchers.Default) {
                    runCatching { assistant.requestSync() }
                    while (isActive) {
                        runCatching {
                            assistant.flushOutbox()
                            assistant.uploadPendingMemos()
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                poller?.cancel()
                poller = null
            }
        })
        appScope.launch {
            audioCoordinator.mode.collectLatest { mode ->
                if (mode == AudioMode.IDLE) return@collectLatest
                while (isActive) {
                    delay(LEASE_TICK_MS)
                    audioCoordinator.tick()
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 8_000L
        const val LEASE_TICK_MS = 2_000L
    }
}

class StageScopeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startBackgroundWork()
    }
}
