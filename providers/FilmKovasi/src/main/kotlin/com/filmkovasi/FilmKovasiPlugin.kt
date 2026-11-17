package com.filmkovasi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmKovasiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmKovasiProvider())
        this.openSettings = { Settings.showDialog(it, "Filmkovası Ayarları", FilmKovasiProvider.ROWS) }
    }
}
