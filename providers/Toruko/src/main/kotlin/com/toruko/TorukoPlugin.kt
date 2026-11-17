package com.toruko

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TorukoPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TorukoProvider())
        this.openSettings = { Settings.showDialog(it, "TORUKO Ayarları", TorukoProvider.ROWS) }
    }
}
