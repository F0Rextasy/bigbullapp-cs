package com.onepacetr

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnepacetrPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(OnepacetrProvider())
        this.openSettings = { Settings.showDialog(it, "Onepacetr Ayarları") }
    }
}
