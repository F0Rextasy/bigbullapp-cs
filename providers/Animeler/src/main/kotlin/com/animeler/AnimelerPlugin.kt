package com.animeler

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimelerPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AnimelerProvider())
        this.openSettings = { Settings.showDialog(it, "Animeler Ayarları", AnimelerProvider.ROWS) }
    }
}
