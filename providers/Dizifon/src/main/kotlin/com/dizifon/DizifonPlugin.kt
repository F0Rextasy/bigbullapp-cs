package com.dizifon

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizifonPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizifonProvider())
        this.openSettings = { Settings.showDialog(it, "Dizifon Ayarları", DizifonProvider.ROWS) }
    }
}
