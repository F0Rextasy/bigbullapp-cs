package com.dizisol

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziSolPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziSolProvider())
        this.openSettings = { Settings.showDialog(it, "DiziSol Ayarları", DiziSolProvider.ROWS) }
    }
}
