package com.turkcedizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TurkceDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TurkceDiziProvider())
        this.openSettings = { Settings.showDialog(it, "TurkceDizi Ayarları", TurkceDiziProvider.ROWS) }
    }
}
