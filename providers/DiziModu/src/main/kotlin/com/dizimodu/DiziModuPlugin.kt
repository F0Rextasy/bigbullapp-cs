package com.dizimodu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziModuPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziModuProvider())
        this.openSettings = { Settings.showDialog(it, "DiziModu Ayarları", DiziModuProvider.ROWS) }
    }
}
