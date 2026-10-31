package com.showtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShowTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(ShowTVProvider())
        this.openSettings = { Settings.showDialog(it, "Show TV Ayarları", ShowTVProvider.ROWS) }
    }
}
