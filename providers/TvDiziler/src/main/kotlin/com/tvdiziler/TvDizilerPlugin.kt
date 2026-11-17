package com.tvdiziler

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TvDizilerPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TvDizilerProvider())
        this.openSettings = { Settings.showDialog(it, "TvDiziler Ayarları", TvDizilerProvider.ROWS) }
    }
}
