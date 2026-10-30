package com.sinemacx

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SinemaCXPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SinemaCXProvider())
        this.openSettings = { Settings.showDialog(it, "SinemaCX Ayarları", SinemaCXProvider.ROWS) }
    }
}
