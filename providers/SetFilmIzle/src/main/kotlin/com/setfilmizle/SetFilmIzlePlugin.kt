package com.setfilmizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SetFilmIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SetFilmIzleProvider())
        this.openSettings = { Settings.showDialog(it, "SetFilmIzle Ayarları") }
    }
}
