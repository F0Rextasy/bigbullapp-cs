package com.puhutv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PuhuTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(PuhuTVProvider())
        this.openSettings = { Settings.showDialog(it, "PuhuTV Ayarları", PuhuTVProvider.ROWS) }
    }
}
