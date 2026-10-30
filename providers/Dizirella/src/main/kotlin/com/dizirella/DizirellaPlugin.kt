package com.dizirella

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizirellaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizirellaProvider())
        this.openSettings = { Settings.showDialog(it, "Dizirella Ayarları", DizirellaProvider.ROWS) }
    }
}
