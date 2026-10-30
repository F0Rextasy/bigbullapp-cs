package com.dizipal

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziPalPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziPalProvider())
        this.openSettings = { Settings.showDialog(it, "DiziPal Ayarları", DiziPalProvider.ROWS) }
    }
}
