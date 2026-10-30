package com.ddizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DdiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DdiziProvider())
        this.openSettings = { Settings.showDialog(it, "Ddizi Ayarları", DdiziProvider.ROWS) }
    }
}
