package com.tvnet

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TvnetPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TvnetProvider())
        this.openSettings = { Settings.showDialog(it, "Tvnet Ayarları", TvnetProvider.ROWS) }
    }
}
