package com.diziabi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziAbiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziAbiProvider())
        this.openSettings = { Settings.showDialog(it, "DiziAbi Ayarları", DiziAbiProvider.ROWS) }
    }
}
