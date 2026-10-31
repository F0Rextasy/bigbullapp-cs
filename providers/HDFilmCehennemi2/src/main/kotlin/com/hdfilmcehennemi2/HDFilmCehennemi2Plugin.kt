package com.hdfilmcehennemi2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmCehennemi2Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(HDFilmCehennemi2Provider())
        this.openSettings = { Settings.showDialog(it, "HDFilmCehennemi2 Ayarları", HDFilmCehennemi2Provider.ROWS) }
    }
}
