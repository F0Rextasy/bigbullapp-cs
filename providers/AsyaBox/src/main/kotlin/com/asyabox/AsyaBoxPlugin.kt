package com.asyabox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaBoxPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaBoxProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaBox Ayarları", AsyaBoxProvider.ROWS) }
    }
}
