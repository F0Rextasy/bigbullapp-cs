package com.asyadizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaDiziProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaDizi Ayarları", AsyaDiziProvider.ROWS) }
    }
}
