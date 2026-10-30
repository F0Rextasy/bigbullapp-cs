package com.lovefilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LoveFilmPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(LoveFilmProvider())
        this.openSettings = { Settings.showDialog(it, "LoveFilm Ayarları") }
    }
}
