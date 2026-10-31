package com.dizigecesi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizigecesiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizigecesiProvider())
        this.openSettings = { Settings.showDialog(it, "Dizigecesi Ayarları", DizigecesiProvider.ROWS) }
    }
}
