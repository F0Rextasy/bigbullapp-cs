package com.dizimanya

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizimanyaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DizimanyaProvider())
        this.openSettings = { Settings.showDialog(it, "Dizimanya Ayarları", DizimanyaProvider.ROWS) }
    }
}
