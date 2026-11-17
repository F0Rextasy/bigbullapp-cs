package com.bicaps

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BiCapsPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(BiCapsProvider())
        this.openSettings = { Settings.showDialog(it, "BiCaps Ayarları", BiCapsProvider.ROWS) }
    }
}
