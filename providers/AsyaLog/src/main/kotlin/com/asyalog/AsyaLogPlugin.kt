package com.asyalog

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaLogPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaLogProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaLog Ayarları", AsyaLogProvider.ROWS) }
    }
}
