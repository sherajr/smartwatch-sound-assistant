package com.peaceantz.stagescope

import android.app.Application
import android.content.Context
import com.peaceantz.stagescope.audio.AudioCaptureEngine
import com.peaceantz.stagescope.audio.PcmSource
import com.peaceantz.stagescope.data.RingBankRepository
import com.peaceantz.stagescope.data.SettingsRepository
import com.peaceantz.stagescope.data.SnapshotRepository
import com.peaceantz.stagescope.data.SurfaceSummaryRepository
import com.peaceantz.stagescope.demo.DemoSignalGenerator

/** Manual constructor-injection container -- deliberately no DI framework for a project this small. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settingsRepository = SettingsRepository(appContext)
    val snapshotRepository = SnapshotRepository(appContext)
    val surfaceSummaryRepository = SurfaceSummaryRepository(appContext)
    val ringBankRepository = RingBankRepository(appContext)
    val audioCaptureEngine = AudioCaptureEngine(appContext)
    val demoSignalGenerator = DemoSignalGenerator()

    /** Chooses the live source based on the current demo-mode setting, read once at Start time. */
    fun pcmSourceFor(demoModeEnabled: Boolean): PcmSource =
        if (demoModeEnabled) demoSignalGenerator else audioCaptureEngine
}

class StageScopeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
