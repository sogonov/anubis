package sgnv.anubis.app.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R

/**
 * Quick Settings tile for the master pause (#145). Separate from
 * [StealthTileService] on purpose — pause is "stop reacting to triggers", not
 * "turn off protection", and conflating them on one tile would teach users to
 * disable Anubis when they meant to pause it.
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val orchestrator = (application as AnubisApp).orchestrator
        orchestrator.setPaused(!orchestrator.paused.value)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val paused = (application as AnubisApp).orchestrator.paused.value
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.pause_tile_label)
        // State-semantic icon: pause glyph when paused, play glyph when active.
        // OEM QS panels render INACTIVE tiles dim — relying on tint alone is
        // unreliable, so we swap the resource explicitly.
        tile.icon = Icon.createWithResource(
            this,
            if (paused) R.drawable.ic_pause else R.drawable.ic_play
        )
        tile.updateTile()
    }
}
