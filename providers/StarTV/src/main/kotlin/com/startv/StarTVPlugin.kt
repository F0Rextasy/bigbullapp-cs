package com.startv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StarTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(StarTVProvider())
        this.openSettings = { Settings.showDialog(it, "StarTV Ayarları", StarTVProvider.ROWS) }
    }
}
