package com.peaceantz.stagescope.assistant.speech

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Activity-side half of dictation: it opens the system screen when the controller says it is ready, and reports back whatever
 * the screen returned. It owns the one `ActivityResultLauncher` and holds no assistant state at all -- launch commands, result
 * interpretation and session state live in [DictationController].
 *
 * Construct it once while the Activity is being created (before it is STARTED), as the Activity Result API requires. Registering
 * in the Activity itself, not in a composable, keeps the result callback in place while the system screen covers StageScope, across
 * recreation (rotation, configuration change) and across process death (the registry hands the result to the new instance).
 *
 * It launches only while the Activity is STARTED, and only for a launch it has atomically claimed ([DictationController.claimLaunch]),
 * so a recreated Activity re-collecting the state can never open a second screen.
 *
 * Lint's InvalidFragmentVersionForActivityResult is suppressed on purpose: it warns about an old `androidx.fragment` (1.2.4 arrives
 * transitively through `androidx.wear:wear`) mishandling request codes in a `FragmentActivity`. The host here is a plain
 * `ComponentActivity` and nothing in this app uses fragments, so the failure it guards against cannot happen.
 */
@SuppressLint("InvalidFragmentVersionForActivityResult")
class DictationLauncherBinding(
    private val activity: ComponentActivity,
    private val controller: DictationController,
    /** The intent to open for a claimed launch. Overridable so an instrumented test can point it at a fake recognition activity. */
    private val intentFor: (LaunchTicket) -> Intent = { DictationIntents.intentFor(it.method, DictationPresentation.prompt(it.task, it.method)) },
) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onResult(DictationIntents.toRaw(result.resultCode, result.data))
    }

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state
                    .map { (it as? DictationState.Active)?.phase }
                    .distinctUntilChanged()
                    .collect { if (it == DictationPhase.AWAITING_LAUNCH) launchIfReady() }
            }
        }
    }

    /** Called when the Activity is going away for good (not a configuration change): the screen can no longer report to it. */
    fun onActivityFinishing() {
        if (activity.isFinishing && !activity.isChangingConfigurations) controller.onHostFinished()
    }

    private fun launchIfReady() {
        val ticket = controller.claimLaunch() ?: return
        try {
            launcher.launch(intentFor(ticket))
            controller.onLaunched(ticket)
        } catch (e: ActivityNotFoundException) {
            controller.onLaunchFailed(ticket, DictationFailureKind.NO_HANDLER)
        } catch (e: SecurityException) {
            controller.onLaunchFailed(ticket, DictationFailureKind.LAUNCH_FAILED)
        } catch (e: RuntimeException) {
            controller.onLaunchFailed(ticket, DictationFailureKind.LAUNCH_FAILED)
        }
    }
}
