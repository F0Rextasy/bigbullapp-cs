package com.filmgecesi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmgecesiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(FilmgecesiProvider())
        this.openSettings = { Settings.showDialog(it, "Filmgecesi Ayarları", FilmgecesiProvider.ROWS) }
    }
}
