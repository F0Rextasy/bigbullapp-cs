package com.wfilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WFilmIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(WFilmIzleProvider())
        this.openSettings = { Settings.showDialog(it, "WFilmIzle Ayarları", WFilmIzleProvider.ROWS) }
    }
}
