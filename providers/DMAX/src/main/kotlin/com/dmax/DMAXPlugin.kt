package com.dmax

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DMAXPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DMAXProvider())
        this.openSettings = { Settings.showDialog(it, "DMAX Ayarları", DMAXProvider.ROWS) }
    }
}
