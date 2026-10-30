package com.fullhdfilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FullHDFilmPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FullHDFilmProvider())
        this.openSettings = { Settings.showDialog(it, "FullHDFilm Ayarları") }
    }
}
