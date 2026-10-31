package com.sinemakolik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SinemakolikPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SinemakolikProvider())
        this.openSettings = { Settings.showDialog(it, "Sinemakolik Ayarları", SinemakolikProvider.ROWS) }
    }
}
