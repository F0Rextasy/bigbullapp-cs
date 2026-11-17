package com.dizikutusu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziKutusuPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziKutusuProvider())
        this.openSettings = { Settings.showDialog(it, "DiziKutusu Ayarları", DiziKutusuProvider.ROWS) }
    }
}
