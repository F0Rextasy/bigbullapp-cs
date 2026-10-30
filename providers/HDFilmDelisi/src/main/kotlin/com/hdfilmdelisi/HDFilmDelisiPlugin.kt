package com.hdfilmdelisi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmDelisiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(HDFilmDelisiProvider())
        this.openSettings = { Settings.showDialog(it, "HDFilmDelisi Ayarları", HDFilmDelisiProvider.ROWS) }
    }
}
