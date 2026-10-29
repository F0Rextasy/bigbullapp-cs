package com.dizibox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziboxPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziboxProvider())
        this.openSettings = { Settings.showDialog(it, "DiziBox Ayarları", DiziboxProvider.ROWS) }
    }
}
