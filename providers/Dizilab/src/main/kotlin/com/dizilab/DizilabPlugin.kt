package com.dizilab

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizilabPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizilabProvider())
        this.openSettings = { Settings.showDialog(it, "Dizilab Ayarları", DizilabProvider.ROWS) }
    }
}
