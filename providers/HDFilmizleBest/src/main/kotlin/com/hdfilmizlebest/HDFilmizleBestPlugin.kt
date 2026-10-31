package com.hdfilmizlebest

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmizleBestPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(HDFilmizleBestProvider())
        this.openSettings = { Settings.showDialog(it, "HDFilmizleBest Ayarları", HDFilmizleBestProvider.ROWS) }
    }
}
