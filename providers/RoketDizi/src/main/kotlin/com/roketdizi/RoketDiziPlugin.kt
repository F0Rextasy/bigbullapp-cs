package com.roketdizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RoketDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(RoketDiziProvider())
        this.openSettings = { Settings.showDialog(it, "RoketDizi Ayarları", RoketDiziProvider.ROWS) }
    }
}
