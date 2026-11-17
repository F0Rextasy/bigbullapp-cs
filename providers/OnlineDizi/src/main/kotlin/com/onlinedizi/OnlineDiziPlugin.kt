package com.onlinedizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlineDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(OnlineDiziProvider())
        this.openSettings = { Settings.showDialog(it, "OnlineDizi Ayarları", OnlineDiziProvider.ROWS) }
    }
}
