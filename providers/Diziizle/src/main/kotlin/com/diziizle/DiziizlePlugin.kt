package com.diziizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziizleProvider())
        this.openSettings = { Settings.showDialog(it, "Diziizle Ayarları", DiziizleProvider.ROWS) }
    }
}
