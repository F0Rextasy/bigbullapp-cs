package com.dizigom

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizigomPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizigomProvider())
        this.openSettings = { Settings.showDialog(it, "Dizigom Ayarları", DizigomProvider.ROWS) }
    }
}
