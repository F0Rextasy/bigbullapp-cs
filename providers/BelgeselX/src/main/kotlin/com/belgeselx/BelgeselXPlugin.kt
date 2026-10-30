package com.belgeselx

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BelgeselXPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(BelgeselXProvider())
        this.openSettings = { Settings.showDialog(it, "BelgeselX Ayarları", BelgeselXProvider.ROWS) }
    }
}
