package com.canliyayinkanallari

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CanliYayinKanallariPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CanliYayinKanallariProvider())
        this.openSettings = { Settings.showDialog(it, "CanliYayinKanallari Ayarları", CanliYayinKanallariProvider.ROWS) }
    }
}
