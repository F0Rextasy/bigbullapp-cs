package com.fullhdfilmizlesene

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FullHDFilmizlesenePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FullHDFilmizleseneProvider())
        this.openSettings = { Settings.showDialog(it, "FullHDFilmizlesene Ayarları") }
    }
}
