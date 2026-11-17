package com.venusfilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VenusFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(VenusFilmizleProvider())
        this.openSettings = { Settings.showDialog(it, "VenusFilmizle Ayarları", VenusFilmizleProvider.ROWS) }
    }
}
