package com.anizium

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniziumPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AniziumProvider())
        this.openSettings = { Settings.showDialog(it, "Anizium Ayarları", AniziumProvider.ROWS) }
    }
}
