package com.asyaanimeleri

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaAnimeleriPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaAnimeleriProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaAnimeleri Ayarları", AsyaAnimeleriProvider.ROWS) }
    }
}
