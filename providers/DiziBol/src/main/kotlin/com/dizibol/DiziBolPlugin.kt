package com.dizibol

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziBolPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziBolProvider())
        this.openSettings = { Settings.showDialog(it, "DiziBol Ayarları", DiziBolProvider.ROWS) }
    }
}
