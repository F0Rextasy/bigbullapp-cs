package com.p720izle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class P720pizlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(P720pizleProvider())
        this.openSettings = { Settings.showDialog(it, "720pizle Ayarları", P720pizleProvider.ROWS) }
    }
}
