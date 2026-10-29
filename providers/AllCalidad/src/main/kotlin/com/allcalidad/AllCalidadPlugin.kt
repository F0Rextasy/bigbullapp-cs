package com.allcalidad

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AllCalidadPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AllCalidadProvider())
        this.openSettings = { Settings.showDialog(it, "AllCalidad Ajustes") }
    }
}
