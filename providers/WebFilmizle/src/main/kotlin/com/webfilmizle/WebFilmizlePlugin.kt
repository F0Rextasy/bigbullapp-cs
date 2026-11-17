package com.webfilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(WebFilmizleProvider())
        this.openSettings = { Settings.showDialog(it, "WebFilmizle Ayarları", WebFilmizleProvider.ROWS) }
    }
}
