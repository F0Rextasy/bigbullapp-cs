package com.rarefilmm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RareFilmmPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(RareFilmmProvider())
        this.openSettings = { Settings.showDialog(it, "RareFilmm Ayarları", RareFilmmProvider.ROWS) }
    }
}
