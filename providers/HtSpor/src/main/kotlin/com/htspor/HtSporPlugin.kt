package com.htspor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HtSporPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(HtSporProvider())
        this.openSettings = { Settings.showDialog(it, "HtSpor Ayarları", HtSporProvider.ROWS) }
    }
}
