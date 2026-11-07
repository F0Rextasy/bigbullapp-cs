package com.rectv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RectvPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(RectvProvider())
        this.openSettings = { Settings.showDialog(it, "RecTV Ayarları", RectvProvider.ROWS.map { it.title }) }
    }
}
