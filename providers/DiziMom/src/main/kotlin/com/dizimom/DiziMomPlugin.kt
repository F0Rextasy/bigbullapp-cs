package com.dizimom

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziMomPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziMomProvider())
        this.openSettings = { Settings.showDialog(it, "DiziMom Ayarları", DiziMomProvider.ROWS) }
    }
}
