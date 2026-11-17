package com.nowtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NowTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(NowTVProvider())
        this.openSettings = { Settings.showDialog(it, "NowTV Ayarları", NowTVProvider.ROWS) }
    }
}
