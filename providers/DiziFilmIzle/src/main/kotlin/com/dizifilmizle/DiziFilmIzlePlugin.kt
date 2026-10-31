package com.dizifilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziFilmIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziFilmIzleProvider())
        this.openSettings = { Settings.showDialog(it, "DiziFilmIzle Ayarları", DiziFilmIzleProvider.ROWS) }
    }
}
