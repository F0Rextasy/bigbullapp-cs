package com.deokwave

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DeokwavePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DeokwaveProvider())
        this.openSettings = { Settings.showDialog(it, "Deokwave Ayarları", DeokwaveProvider.ROWS) }
    }
}
