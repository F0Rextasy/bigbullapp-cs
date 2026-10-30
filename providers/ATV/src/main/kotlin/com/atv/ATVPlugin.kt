package com.atv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ATVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(ATVProvider())
        this.openSettings = { Settings.showDialog(it, "ATV Ayarları", ATVProvider.ROWS) }
    }
}
