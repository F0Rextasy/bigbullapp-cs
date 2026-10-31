package com.minikacocuk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MinikaCocukPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(MinikaCocukProvider())
        this.openSettings = { Settings.showDialog(it, "MinikaCocuk Ayarları", MinikaCocukProvider.ROWS) }
    }
}
