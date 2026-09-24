package com.peaceantz.stagescope.ui.rotation

/**
 * Pure angle math for the app-wide crown-driven rotation (see [OrientationViewModel]). The live
 * angle that drives `Modifier.graphicsLayer { rotationZ = ... }` is kept UNWRAPPED -- it can grow
 * past 360 or go negative as the crown keeps turning one way -- so continuous rotation never
 * produces a visual jump at the 0/360 seam. Normalizing into [0, 360) only happens when persisting
 * or displaying a settled value, never on the live render path.
 */
object OrientationMath {
    /** Initial engineering value: degrees of rotation per raw rotary scroll pixel. */
    const val DEGREES_PER_ROTARY_PIXEL = 0.24f

    /** Applies one crown event's raw scroll pixels to the current unwrapped angle. */
    fun applyDelta(currentDegrees: Float, rotaryScrollPixels: Float): Float =
        currentDegrees + rotaryScrollPixels * DEGREES_PER_ROTARY_PIXEL

    /** Normalizes to [0, 360) -- for persistence/display only, never for the live rotationZ value. */
    fun normalize(degrees: Float): Float {
        var result = degrees % 360f
        if (result < 0f) result += 360f
        return result
    }
}
