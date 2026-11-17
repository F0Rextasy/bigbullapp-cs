package com.tv2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TV2Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TV2Provider())
        this.openSettings = { Settings.showDialog(it, "TV2 Ayarları", TV2Provider.ROWS) }
    }
}
