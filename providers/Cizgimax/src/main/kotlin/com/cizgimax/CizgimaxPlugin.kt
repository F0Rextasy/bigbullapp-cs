package com.cizgimax

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CizgimaxPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CizgimaxProvider())
        this.openSettings = { Settings.showDialog(it, "ÇizgiMax Ayarları", CizgimaxProvider.ROWS) }
    }
}
