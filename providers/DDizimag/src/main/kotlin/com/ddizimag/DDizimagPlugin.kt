package com.ddizimag

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DDizimagPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DDizimagProvider())
        this.openSettings = { Settings.showDialog(it, "DDizimag Ayarları", DDizimagProvider.ROWS) }
    }
}
