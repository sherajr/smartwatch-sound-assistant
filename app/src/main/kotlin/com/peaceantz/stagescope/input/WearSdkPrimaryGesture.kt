package com.peaceantz.stagescope.input

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.google.wear.Sdk
import com.google.wear.input.GestureEvent
import com.google.wear.input.GestureInputManager
import java.util.function.Consumer

/**
 * The real [PrimaryGestureSource]: the Wear SDK's `GestureInputManager`, a system library on Wear OS 7+ watches. It is
 * the same call Wear Compose 1.7's `Modifier.oneHandedGesture` makes underneath (feature check, `getWearManager`,
 * `isActionSupported`, `addGestureEventListener` for one View, `notifyGestureConsumed` after acting); that library needs
 * AGP 9.1 and compileSdk 37, so StageScope calls the SDK directly, compiled against SDK platform 37's stub (see
 * app/build.gradle.kts). This is the only file that touches `com.google.wear`.
 *
 * Every call is guarded: a watch without the Wear SDK, or with one that predates gestures, throws
 * NoClassDefFoundError/NoSuchMethodError on first use, and the gesture is then simply unavailable.
 *
 * No haptic on a gesture (the Compose library adds one): StageScope is measuring the room through the watch's own
 * microphone at that moment, and the vibration motor would be in the reading -- and could even be caught as a ring.
 */
class WearSdkPrimaryGesture(context: Context) : PrimaryGestureSource {
    private val appContext = context.applicationContext

    private val manager: GestureInputManager? by lazy {
        try {
            if (Sdk.hasApiFeature(Sdk.FEATURE_WEAR_GESTURE_DETECTION)) {
                Sdk.getWearManager(appContext, GestureInputManager::class.java)
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    override fun isAvailable(): Boolean = try {
        val manager = manager
        manager != null &&
            manager.isActionSupported(GestureEvent.ACTION_PRIMARY) &&
            manager.isActionEnabled(GestureEvent.ACTION_PRIMARY)
    } catch (t: Throwable) {
        false
    }

    override fun subscribe(view: View, gestureId: String, onGesture: (atUptimeMillis: Long) -> Unit): AutoCloseable? {
        if (!isAvailable()) {
            Log.d(TAG, "primary gesture: unavailable")
            return null
        }
        return try {
            val manager = manager ?: return null
            var clockLogged = false
            val listener = Consumer<GestureEvent> { event ->
                if (event.action == GestureEvent.ACTION_PRIMARY) {
                    val resolved = PrimaryGestureTiming.resolve(event.eventTime, SystemClock.uptimeMillis())
                    if (!clockLogged) {
                        clockLogged = true
                        Log.d(TAG, "primary gesture: clock=${resolved.clock}")
                    }
                    onGesture(resolved.atUptimeMillis)
                    try {
                        manager.notifyGestureConsumed(gestureId, GestureEvent.ACTION_PRIMARY)
                    } catch (t: Throwable) {
                        // Only the system's hint bookkeeping; the beat has already been counted.
                    }
                }
            }
            manager.addGestureEventListener(
                intArrayOf(GestureEvent.ACTION_PRIMARY),
                view,
                ContextCompat.getMainExecutor(appContext),
                listener,
            )
            Log.d(TAG, "primary gesture: subscribed")
            AutoCloseable {
                try {
                    manager.removeGestureEventListener(listener)
                } catch (t: Throwable) {
                    // The listener dies with the view anyway.
                }
                Log.d(TAG, "primary gesture: unsubscribed")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "primary gesture: subscribe failed (${t.javaClass.simpleName})")
            null
        }
    }

    companion object {
        /** Diagnostics: subscription state and which clock stamps the gestures -- never anything else. */
        const val TAG = "StageScope/Tempo"
    }
}
