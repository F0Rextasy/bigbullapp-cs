package com.filmmakinesi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmMakinesiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmMakinesiProvider())
        this.openSettings = { Settings.showDialog(it, "FilmMakinesi Ayarları", FilmMakinesiProvider.ROWS) }
    }
}
