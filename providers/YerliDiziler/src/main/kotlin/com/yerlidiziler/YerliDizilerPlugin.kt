package com.yerlidiziler

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YerliDizilerPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(YerliDizilerProvider())
        this.openSettings = { Settings.showDialog(it, "YerliDiziler Ayarları", YerliDizilerProvider.ROWS) }
    }
}
