package com.jetfilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JetFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(JetFilmizleProvider())
        this.openSettings = { Settings.showDialog(it, "JetFilmizle Ayarları", JetFilmizleProvider.ROWS) }
    }
}
