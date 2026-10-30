package com.tgrthaber

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TgrtHaberPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TgrtHaberProvider())
        this.openSettings = { Settings.showDialog(it, "TgrtHaber Ayarları", TgrtHaberProvider.ROWS) }
    }
}
