package com.canlitv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CanliTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CanliTVProvider())
        this.openSettings = { Settings.showDialog(it, "CanliTV Ayarları", CanliTVProvider.ROWS) }
    }
}
