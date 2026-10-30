package com.asyawatch

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaWatchPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaWatchProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaWatch Ayarları", AsyaWatchProvider.ROWS) }
    }
}
