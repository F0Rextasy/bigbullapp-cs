package com.weboo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebooPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(WebooProvider())
        this.openSettings = { Settings.showDialog(it, "Weboo Ayarları", WebooProvider.ROWS) }
    }
}
