package com.canlitvme

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CanliTVMePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CanliTVMeProvider())
        this.openSettings = { Settings.showDialog(it, "CanliTVMe Ayarları", CanliTVMeProvider.ROWS) }
    }
}
