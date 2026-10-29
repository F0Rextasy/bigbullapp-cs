package com.kultfilmler

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KultfilmlerPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(KultfilmlerProvider())
        this.openSettings = { Settings.showDialog(it, "Kultfilmler Ayarları", KultfilmlerProvider.ROWS) }
    }
}
