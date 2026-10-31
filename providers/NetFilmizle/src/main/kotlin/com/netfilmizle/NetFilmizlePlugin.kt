package com.netfilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(NetFilmizleProvider())
        this.openSettings = { Settings.showDialog(it, "NetFilmizle Ayarları", NetFilmizleProvider.ROWS) }
    }
}
