package com.yabancidizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YabancidiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(YabancidiziProvider())
        this.openSettings = { Settings.showDialog(it, "Yabancidizi Ayarları") }
    }
}
