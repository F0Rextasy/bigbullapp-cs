package com.kanald

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KanalDPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(KanalDProvider())
        this.openSettings = { Settings.showDialog(it, "KanalD Ayarları", KanalDProvider.ROWS) }
    }
}
