package com.tv8bucuk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Tv8BucukPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(Tv8BucukProvider())
        this.openSettings = { Settings.showDialog(it, "Tv8Bucuk Ayarları", Tv8BucukProvider.ROWS) }
    }
}
