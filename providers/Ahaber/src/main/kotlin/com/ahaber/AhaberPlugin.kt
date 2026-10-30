package com.ahaber

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AhaberPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AhaberProvider())
        this.openSettings = { Settings.showDialog(it, "Ahaber Ayarları", AhaberProvider.ROWS) }
    }
}
