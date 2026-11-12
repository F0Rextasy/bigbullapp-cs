package com.dizist

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizistPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizistProvider())
        this.openSettings = { Settings.showDialog(it, "Dizist Ayarları", DizistProvider.ROWS) }
    }
}
