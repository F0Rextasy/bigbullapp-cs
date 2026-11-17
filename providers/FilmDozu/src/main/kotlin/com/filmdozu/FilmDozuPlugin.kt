package com.filmdozu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmDozuPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmDozuProvider())
        this.openSettings = { Settings.showDialog(it, "FilmDozu Ayarları", FilmDozuProvider.ROWS) }
    }
}
