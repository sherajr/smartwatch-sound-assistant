package com.peaceantz.stagescope.widget

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.peaceantz.stagescope.widget.complication.StageScopeComplicationService
import com.peaceantz.stagescope.widget.tile.StageScopeTileService

/**
 * Nudges the Tile and complication to re-render right after a meaningful change is persisted to
 * [com.peaceantz.stagescope.data.SurfaceSummaryRepository] (Level stop, Ring capture/pin/unpin/
 * clear) -- called from the ViewModels that own those events, not on a timer. Both requests are
 * cheap, coalesced hints to the system; neither touches the microphone or blocks on I/O.
 */
fun notifyWatchSurfacesChanged(context: Context) {
    TileService.getUpdater(context).requestUpdate(StageScopeTileService::class.java)
    ComplicationDataSourceUpdateRequester.create(
        context,
        ComponentName(context, StageScopeComplicationService::class.java),
    ).requestUpdateAll()
}
