package com.ultrafilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UltraFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(UltraFilmizleProvider())
        this.openSettings = { Settings.showDialog(it, "UltraFilmizle Ayarları", UltraFilmizleProvider.ROWS) }
    }
}
