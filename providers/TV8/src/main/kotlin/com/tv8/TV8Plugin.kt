package com.tv8

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TV8Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TV8Provider())
        this.openSettings = { Settings.showDialog(it, "TV8 Ayarları", TV8Provider.ROWS) }
    }
}
