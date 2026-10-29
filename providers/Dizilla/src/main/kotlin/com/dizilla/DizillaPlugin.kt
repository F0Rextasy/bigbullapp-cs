package com.dizilla

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizillaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizillaProvider())
        this.openSettings = { Settings.showDialog(it, "Dizilla Ayarları", DizillaProvider.ROWS) }
    }
}
