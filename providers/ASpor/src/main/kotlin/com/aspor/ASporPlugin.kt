package com.aspor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ASporPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(ASporProvider())
        this.openSettings = { Settings.showDialog(it, "ASpor Ayarları", ASporProvider.ROWS) }
    }
}
