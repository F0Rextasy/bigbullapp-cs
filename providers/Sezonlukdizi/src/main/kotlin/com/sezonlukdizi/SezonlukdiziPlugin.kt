package com.sezonlukdizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SezonlukdiziPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SezonlukdiziProvider())
        this.openSettings = { Settings.showDialog(it, "Sezonlukdizi Ayarları", SezonlukdiziProvider.ROWS) }
    }
}
