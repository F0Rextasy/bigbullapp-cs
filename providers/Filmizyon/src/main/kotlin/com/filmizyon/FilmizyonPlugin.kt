package com.filmizyon

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmizyonPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmizyonProvider())
        this.openSettings = { Settings.showDialog(it, "Filmizyon Ayarları", FilmizyonProvider.ROWS) }
    }
}
