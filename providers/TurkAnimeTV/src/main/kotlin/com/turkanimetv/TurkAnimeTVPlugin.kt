package com.turkanimetv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TurkAnimeTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TurkAnimeTVProvider())
        this.openSettings = { Settings.showDialog(it, "Türk Anime TV Ayarları", TurkAnimeTVProvider.ROWS) }
    }
}
