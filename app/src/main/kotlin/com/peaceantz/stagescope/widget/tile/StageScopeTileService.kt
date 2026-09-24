package com.peaceantz.stagescope.widget.tile

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.CardDefaults.filledTonalCardColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.DataCardStyle
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textDataCard
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.peaceantz.stagescope.MainActivity
import com.peaceantz.stagescope.StageScopeApp
import com.peaceantz.stagescope.data.SurfaceSummary
import com.peaceantz.stagescope.ui.nav.EXTRA_RING_CAPTURE_ID
import com.peaceantz.stagescope.ui.nav.EXTRA_SHORTCUT
import com.peaceantz.stagescope.ui.nav.SHORTCUT_MEASURE
import com.peaceantz.stagescope.ui.nav.SHORTCUT_OPEN_RING
import com.peaceantz.stagescope.ui.nav.SHORTCUT_OPEN_SPECTRUM
import com.peaceantz.stagescope.ui.theme.StageScopePalette
import com.peaceantz.stagescope.ui.theme.StageScopePalettes
import com.peaceantz.stagescope.widget.formatDbCompact
import com.peaceantz.stagescope.widget.formatFrequencyReadable
import com.peaceantz.stagescope.widget.formatWhen

private const val RESOURCES_VERSION = "1"

/**
 * The StageScope Tile: a swipeable surface beside the watch face showing the last saved reading,
 * a compact ring summary, and shortcuts into the app. Built on the stable Tiles + ProtoLayout
 * Material3 APIs (`androidx.wear.tiles.TileService` binding the system, `androidx.wear.protolayout`
 * builders for the actual layout) -- this project has no separate "Wear Widgets" surface, so this
 * is the one carousel entry point; see docs/STATUS.md for why.
 *
 * Reads [com.peaceantz.stagescope.data.SurfaceSummaryRepository] only -- never the microphone or
 * the live capture session -- so simply rendering or refreshing this Tile can't start recording.
 */
class StageScopeTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        val container = (applicationContext as StageScopeApp).container
        val summary = container.surfaceSummaryRepository.summary.value
        val settings = container.settingsRepository.settings.value

        val layout = materialScope(
            context = this,
            deviceConfiguration = requestParams.deviceConfiguration,
            allowDynamicTheme = false,
            defaultColorScheme = stageScopeTileColorScheme(StageScopePalettes.forTheme(settings.theme), settings.dimAppearanceEnabled),
        ) {
            primaryLayout(
                titleSlot = {
                    text(
                        text = "STAGESCOPE".layoutString,
                        typography = Typography.LABEL_SMALL,
                        color = colorScheme.onSurfaceVariant,
                    )
                },
                mainSlot = { tileMainContent(this@StageScopeTileService, summary) },
                bottomSlot = {
                    textEdgeButton(
                        onClick = shortcutClickable(this@StageScopeTileService, "measure", SHORTCUT_MEASURE),
                        colors = filledButtonColors(),
                    ) {
                        text("MEASURE".layoutString)
                    }
                },
            )
        }

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(Timeline.fromLayoutElement(layout))
            .build()
        return immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<Resources> =
        immediateFuture(Resources.Builder().setVersion(RESOURCES_VERSION).build())
}

/**
 * Everything this Tile needs (the persisted summary) is already in memory by the time
 * onTileRequest runs, so there's no async work -- this just hands the already-computed value back
 * as a [ListenableFuture] without pulling in full Guava for a single `Futures.immediateFuture`.
 */
private fun <T> immediateFuture(value: T): ListenableFuture<T> =
    CallbackToFutureAdapter.getFuture { completer ->
        completer.set(value)
        "stagescope-tile-immediate-future"
    }

private fun MaterialScope.tileMainContent(context: Context, summary: SurfaceSummary): LayoutElement {
    val column = Column.Builder().setWidth(expand()).setHeight(wrap())

    val reading = summary.lastReading
    if (reading != null) {
        column.addContent(
            textDataCard(
                onClick = shortcutClickable(context, "measure_card", SHORTCUT_MEASURE),
                width = expand(),
                style = DataCardStyle.smallCompactDataCardStyle(),
                colors = filledTonalCardColors(),
                title = { text(formatDbCompact(reading.rmsDisplayDbfs).layoutString) },
                content = { text(reading.unitLabel.layoutString) },
                secondaryText = { text("Last reading · ${formatWhen(reading.timestampMillis)}".layoutString) },
            )
        )
    } else {
        column.addContent(
            text(
                text = "Ready to measure".layoutString,
                typography = Typography.LABEL_LARGE,
                color = colorScheme.onSurfaceVariant,
            )
        )
    }

    val ring = summary.ringSummary
    if (ring != null) {
        column.addContent(Spacer.Builder().setHeight(SPACER_DP).build())
        val pinnedNote = if (ring.pinned) " · pinned" else ""
        column.addContent(
            text(
                text = "Top ring · ${formatFrequencyReadable(ring.frequencyHz)}$pinnedNote".layoutString,
                typography = Typography.LABEL_MEDIUM,
                color = colorScheme.onSurfaceVariant,
            )
        )
    }

    column.addContent(Spacer.Builder().setHeight(SPACER_DP).build())
    column.addContent(
        buttonGroup {
            buttonGroupItem {
                compactButton(
                    onClick = shortcutClickable(context, "analyzer", SHORTCUT_OPEN_SPECTRUM),
                    labelContent = { text("ANALYZER".layoutString) },
                    width = weight(1f),
                    colors = filledTonalButtonColors(),
                )
            }
            buttonGroupItem {
                compactButton(
                    onClick = shortcutClickable(context, "ring", SHORTCUT_OPEN_RING, ring?.captureId),
                    labelContent = { text("RING".layoutString) },
                    width = weight(1f),
                    colors = filledTonalButtonColors(),
                )
            }
        }
    )

    return column.build()
}

private val SPACER_DP = dp(6f)

/**
 * Declarative launch: the system (not this process) constructs the actual Intent/PendingIntent
 * from this package/class/extras spec, so we can't set a custom Intent action here -- routing
 * goes through [EXTRA_SHORTCUT] instead, the same contract the complication's real PendingIntents
 * use. Each shortcut gets its own Clickable [id] so the system treats them as distinct targets.
 */
private fun shortcutClickable(
    context: Context,
    id: String,
    shortcut: String,
    ringCaptureId: Long? = null,
): Clickable {
    val extras = mutableMapOf<String, ActionBuilders.AndroidExtra>(
        EXTRA_SHORTCUT to ActionBuilders.stringExtra(shortcut),
    )
    ringCaptureId?.let { extras[EXTRA_RING_CAPTURE_ID] = ActionBuilders.longExtra(it) }
    val action = ActionBuilders.launchAction(ComponentName(context, MainActivity::class.java), extras)
    return Clickable.Builder().setId(id).setOnClick(action).build()
}

/** Reuses the app's own active palette + Dim setting (ui/theme/Theme.kt) so the Tile visibly
 *  follows the in-app theme choice, not just the app's own buttons. */
private fun stageScopeTileColorScheme(palette: StageScopePalette, dim: Boolean): ColorScheme {
    val live = if (dim) palette.LiveDim else palette.Live
    val held = if (dim) palette.HeldDim else palette.Held
    val primaryText = if (dim) palette.PrimaryTextDim else palette.PrimaryText
    val secondaryText = if (dim) palette.SecondaryTextDim else palette.SecondaryText
    val grid = if (dim) palette.GridDim else palette.Grid
    val black = android.graphics.Color.BLACK.argb
    return ColorScheme(
        primary = live.toArgb().argb,
        onPrimary = black,
        primaryContainer = palette.Surface.toArgb().argb,
        onPrimaryContainer = primaryText.toArgb().argb,
        secondary = secondaryText.toArgb().argb,
        onSecondary = black,
        surfaceContainer = palette.Surface.toArgb().argb,
        surfaceContainerLow = palette.Surface.toArgb().argb,
        surfaceContainerHigh = palette.Surface.toArgb().argb,
        onSurface = primaryText.toArgb().argb,
        onSurfaceVariant = secondaryText.toArgb().argb,
        outline = grid.toArgb().argb,
        outlineVariant = grid.toArgb().argb,
        background = palette.Background.toArgb().argb,
        onBackground = primaryText.toArgb().argb,
        error = held.toArgb().argb,
        errorContainer = held.toArgb().argb,
        onError = black,
        onErrorContainer = black,
    )
}
