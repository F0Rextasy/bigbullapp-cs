package com.vavtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VAVTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(VAVTVProvider())
        this.openSettings = { Settings.showDialog(it, "VAVTV Ayarları", VAVTVProvider.ROWS) }
    }
}
