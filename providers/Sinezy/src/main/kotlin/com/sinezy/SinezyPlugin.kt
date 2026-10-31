package com.sinezy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SinezyPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SinezyProvider())
        this.openSettings = { Settings.showDialog(it, "Sinezy Ayarları", SinezyProvider.ROWS) }
    }
}
