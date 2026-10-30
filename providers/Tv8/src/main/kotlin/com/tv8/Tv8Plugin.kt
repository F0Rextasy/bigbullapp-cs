package com.tv8

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Tv8Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(Tv8Provider())
        this.openSettings = { Settings.showDialog(it, "Tv8 Ayarları", Tv8Provider.ROWS) }
    }
}
