package com.sonanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SonAnimePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SonAnimeProvider())
        this.openSettings = { Settings.showDialog(it, "SonAnime Ayarları", SonAnimeProvider.ROWS) }
    }
}
