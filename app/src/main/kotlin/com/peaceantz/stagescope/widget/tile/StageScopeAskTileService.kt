package com.peaceantz.stagescope.widget.tile

import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.peaceantz.stagescope.StageScopeApp
import com.peaceantz.stagescope.ui.nav.SHORTCUT_ASK_AI
import com.peaceantz.stagescope.ui.theme.StageScopePalettes

private const val ASK_RESOURCES_VERSION = "1"

/**
 * An optional second Tile: a shortcut to the Assistant page. It only ever *opens* that page -- the
 * microphone starts solely from the mic button inside the app -- and it reads only the assistant's
 * cached last answer from local storage, so rendering or refreshing it neither records nor contacts anything.
 */
class StageScopeAskTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        val container = (applicationContext as StageScopeApp).container
        val settings = container.settingsRepository.settings.value
        val latest = container.assistant.cache.value.threads.firstOrNull()

        val layout = materialScope(
            context = this,
            deviceConfiguration = requestParams.deviceConfiguration,
            allowDynamicTheme = false,
            defaultColorScheme = stageScopeTileColorScheme(StageScopePalettes.forTheme(settings.theme), settings.dimAppearanceEnabled),
        ) {
            primaryLayout(
                titleSlot = {
                    text("ASSISTANT".layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.onSurfaceVariant)
                },
                mainSlot = {
                    Column.Builder().setWidth(expand()).setHeight(wrap())
                        .addContent(
                            text(
                                text = (latest?.summary ?: "Ask about the sound, log an issue, or draft a note.").layoutString,
                                typography = Typography.BODY_MEDIUM,
                                color = colorScheme.onSurface,
                                maxLines = 4,
                            ),
                        )
                        .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                        .addContent(
                            text(
                                text = (if (latest != null) "${latest.providerLabel} · ${latest.modelId}" else "Opens the assistant. The microphone starts only when you tap its mic.").layoutString,
                                typography = Typography.LABEL_SMALL,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            ),
                        )
                        .build()
                },
                bottomSlot = {
                    textEdgeButton(
                        onClick = shortcutClickable(this@StageScopeAskTileService, "ask_ai", SHORTCUT_ASK_AI),
                        colors = filledButtonColors(),
                    ) {
                        text("ASK AI".layoutString)
                    }
                },
            )
        }

        val tile = Tile.Builder()
            .setResourcesVersion(ASK_RESOURCES_VERSION)
            .setTileTimeline(Timeline.fromLayoutElement(layout))
            .setFreshnessIntervalMillis(5 * 60_000L)
            .build()
        return immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<Resources> =
        immediateFuture(Resources.Builder().setVersion(ASK_RESOURCES_VERSION).build())
}
