package com.apara

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AParaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AParaProvider())
        this.openSettings = { Settings.showDialog(it, "APara Ayarları", AParaProvider.ROWS) }
    }
}
