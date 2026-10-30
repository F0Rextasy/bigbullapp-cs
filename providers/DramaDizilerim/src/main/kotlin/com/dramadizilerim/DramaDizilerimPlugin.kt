package com.dramadizilerim

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaDizilerimPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DramaDizilerimProvider())
        this.openSettings = { Settings.showDialog(it, "DramaDizilerim Ayarları", DramaDizilerimProvider.ROWS) }
    }
}
