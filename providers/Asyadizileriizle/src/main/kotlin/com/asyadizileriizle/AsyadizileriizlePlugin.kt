package com.asyadizileriizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyadizileriizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyadizileriizleProvider())
        this.openSettings = { Settings.showDialog(it, "Asyadizileriizle Ayarları", AsyadizileriizleProvider.ROWS) }
    }
}
