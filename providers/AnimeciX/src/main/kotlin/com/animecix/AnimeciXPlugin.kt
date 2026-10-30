package com.animecix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeciXPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AnimeciXProvider())
        this.openSettings = { Settings.showDialog(it, "AnimeciX Ayarları") }
    }
}
