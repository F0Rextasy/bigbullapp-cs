package com.dizikorea3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Dizikorea3Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(Dizikorea3Provider())
        this.openSettings = { Settings.showDialog(it, "Dizikorea3 Ayarları", Dizikorea3Provider.ROWS) }
        registerExtractorAPI(VidmolyOrg())
    }
}
