package com.minikago

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MinikaGOPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(MinikaGOProvider())
        this.openSettings = { Settings.showDialog(it, "MinikaGO Ayarları", MinikaGOProvider.ROWS) }
    }
}
