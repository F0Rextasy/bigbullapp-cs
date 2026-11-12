package com.izlefilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IzleFilmPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(IzleFilmProvider())
        this.openSettings = { Settings.showDialog(it, "IzleFilm Ayarları", IzleFilmProvider.ROWS) }
    }
}
