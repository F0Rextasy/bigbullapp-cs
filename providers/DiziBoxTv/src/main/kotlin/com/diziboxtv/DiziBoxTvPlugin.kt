package com.diziboxtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziBoxTvPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziBoxTvProvider())
        this.openSettings = { Settings.showDialog(it, "DiziBoxTv Ayarları", DiziBoxTvProvider.ROWS) }
    }
}
