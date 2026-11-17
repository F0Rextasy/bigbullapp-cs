package com.izle7

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Izle7Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(Izle7Provider())
        this.openSettings = { Settings.showDialog(it, "İzle7 Ayarları", Izle7Provider.ROWS) }
    }
}
