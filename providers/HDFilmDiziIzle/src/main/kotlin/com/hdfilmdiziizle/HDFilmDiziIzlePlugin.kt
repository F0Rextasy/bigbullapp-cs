package com.hdfilmdiziizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmDiziIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(HDFilmDiziIzleProvider())
        this.openSettings = { Settings.showDialog(it, "HDFilmDiziIzle Ayarları", HDFilmDiziIzleProvider.ROWS) }
    }
}
