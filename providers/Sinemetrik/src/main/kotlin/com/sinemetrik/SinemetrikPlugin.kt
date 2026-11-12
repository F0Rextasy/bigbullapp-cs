package com.sinemetrik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SinemetrikPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SinemetrikProvider())
        this.openSettings = { Settings.showDialog(it, "Sinemetrik Ayarları", SinemetrikProvider.ROWS) }
    }
}
