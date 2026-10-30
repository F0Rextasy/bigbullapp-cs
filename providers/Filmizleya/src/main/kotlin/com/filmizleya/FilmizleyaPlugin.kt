package com.filmizleya

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmizleyaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmizleyaProvider())
        this.openSettings = { Settings.showDialog(it, "Filmizleya Ayarları", FilmizleyaProvider.ROWS) }
    }
}
