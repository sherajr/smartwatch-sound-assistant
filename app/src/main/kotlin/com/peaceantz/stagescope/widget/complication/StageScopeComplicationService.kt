package com.peaceantz.stagescope.widget.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.peaceantz.stagescope.MainActivity
import com.peaceantz.stagescope.R
import com.peaceantz.stagescope.StageScopeApp
import com.peaceantz.stagescope.data.RingSummaryState
import com.peaceantz.stagescope.ui.nav.EXTRA_RING_CAPTURE_ID
import com.peaceantz.stagescope.ui.nav.EXTRA_SHORTCUT
import com.peaceantz.stagescope.ui.nav.SHORTCUT_OPEN_LEVEL
import com.peaceantz.stagescope.ui.nav.SHORTCUT_OPEN_RING
import com.peaceantz.stagescope.widget.formatFrequencyCompact
import com.peaceantz.stagescope.widget.formatFrequencyReadable
import com.peaceantz.stagescope.widget.formatWhen

private const val REQUEST_CODE_OPEN_LEVEL = 401
private const val REQUEST_CODE_OPEN_RING = 402

/**
 * The StageScope watch-face complication: shows the pinned (or otherwise most recent) saved ring
 * frequency and acts as a shortcut into the app. Shares [com.peaceantz.stagescope.data.SurfaceSummaryRepository]
 * with the Tile -- this is historical information read from disk, never a live feed, so simply
 * being displayed or refreshed by the watch face never touches the microphone.
 */
class StageScopeComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val container = (applicationContext as StageScopeApp).container
        val ring = container.surfaceSummaryRepository.summary.value.ringSummary
        return buildComplicationData(this, request.complicationType, ring)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sample = RingSummaryState(
            captureId = -1L,
            frequencyHz = 326.0,
            timestampMillis = System.currentTimeMillis(),
            pinned = true,
        )
        return buildComplicationData(this, type, sample)
    }
}

private fun buildComplicationData(
    context: Context,
    type: ComplicationType,
    ring: RingSummaryState?,
): ComplicationData? = when (type) {
    ComplicationType.SHORT_TEXT -> buildShortText(context, ring)
    ComplicationType.MONOCHROMATIC_IMAGE -> buildMonochromaticImage(context, ring)
    else -> null
}

private fun buildShortText(context: Context, ring: RingSummaryState?): ShortTextComplicationData {
    if (ring == null) {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("StageScope").build(),
            contentDescription = PlainComplicationText.Builder(
                "StageScope. No saved ring frequency yet. Opens Level."
            ).build(),
        )
            .setTitle(PlainComplicationText.Builder("SS").build())
            .setTapAction(openLevelPendingIntent(context))
            .build()
    }
    val statusWord = if (ring.pinned) "Pinned" else "Last"
    return ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(formatFrequencyCompact(ring.frequencyHz)).build(),
        contentDescription = PlainComplicationText.Builder(
            "$statusWord ring ${formatFrequencyReadable(ring.frequencyHz)}, saved ${formatWhen(ring.timestampMillis)}."
        ).build(),
    )
        .setTitle(PlainComplicationText.Builder(if (ring.pinned) "PIN" else "LAST").build())
        .setTapAction(openRingPendingIntent(context, ring.captureId))
        .build()
}

private fun buildMonochromaticImage(
    context: Context,
    ring: RingSummaryState?,
): MonochromaticImageComplicationData {
    val image = MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_stagescope_mono)).build()
    return if (ring == null) {
        MonochromaticImageComplicationData.Builder(
            monochromaticImage = image,
            contentDescription = PlainComplicationText.Builder(
                "StageScope. No saved ring frequency yet. Opens Level."
            ).build(),
        )
            .setTapAction(openLevelPendingIntent(context))
            .build()
    } else {
        val statusWord = if (ring.pinned) "Pinned" else "Last"
        MonochromaticImageComplicationData.Builder(
            monochromaticImage = image,
            contentDescription = PlainComplicationText.Builder(
                "$statusWord ring ${formatFrequencyReadable(ring.frequencyHz)}, saved ${formatWhen(ring.timestampMillis)}."
            ).build(),
        )
            .setTapAction(openRingPendingIntent(context, ring.captureId))
            .build()
    }
}

/** Icon-only / no-data tap target: opens LEVEL without ever starting the microphone on its own. */
private fun openLevelPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_SHORTCUT, SHORTCUT_OPEN_LEVEL)
    return PendingIntent.getActivity(
        context,
        REQUEST_CODE_OPEN_LEVEL,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Opens RING and re-selects [captureId] if that capture is still live in the current session. */
private fun openRingPendingIntent(context: Context, captureId: Long): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_SHORTCUT, SHORTCUT_OPEN_RING)
        .putExtra(EXTRA_RING_CAPTURE_ID, captureId)
    return PendingIntent.getActivity(
        context,
        REQUEST_CODE_OPEN_RING,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
