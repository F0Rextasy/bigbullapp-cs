package com.cizgivedizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CizgiVeDiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CizgiVeDiziProvider())
        this.openSettings = { Settings.showDialog(it, "CizgiVeDizi Ayarları", CizgiVeDiziProvider.ROWS) }
    }
}
