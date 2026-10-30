package com.hdfilmcehennemi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmCehennemiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(HDFilmCehennemiProvider())
        this.openSettings = { Settings.showDialog(it, "HDFilmCehennemi Ayarları", HDFilmCehennemiProvider.ROWS) }
    }
}
