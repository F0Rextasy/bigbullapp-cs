package com.canlidizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CanliDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CanliDiziProvider())
        this.openSettings = { Settings.showDialog(it, "CanliDizi Ayarları", CanliDiziProvider.ROWS) }
    }
}
