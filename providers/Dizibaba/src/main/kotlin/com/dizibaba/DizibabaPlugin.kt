package com.dizibaba

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizibabaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizibabaProvider())
        this.openSettings = { Settings.showDialog(it, "Dizibaba Ayarları", DizibabaProvider.ROWS) }
    }
}
