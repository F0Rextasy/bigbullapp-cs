package com.asyaluna

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaLunaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaLunaProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaLuna Ayarları", AsyaLunaProvider.ROWS) }
    }
}
