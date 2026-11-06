package com.webteizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebteIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(WebteIzleProvider())
        this.openSettings = { Settings.showDialog(it, "WebteIzle Ayarları", WebteIzleProvider.ROWS) }
    }
}
