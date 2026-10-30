package com.birasyadizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BirAsyaDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(BirAsyaDiziProvider())
        this.openSettings = { Settings.showDialog(it, "BirAsyaDizi Ayarları", BirAsyaDiziProvider.ROWS) }
    }
}
