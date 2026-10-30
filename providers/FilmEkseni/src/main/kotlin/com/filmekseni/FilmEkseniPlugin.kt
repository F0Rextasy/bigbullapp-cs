package com.filmekseni

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmEkseniPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmEkseniProvider())
        this.openSettings = { Settings.showDialog(it, "FilmEkseni Ayarları") }
    }
}
