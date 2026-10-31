package com.aniarsiv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniArsivPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AniArsivProvider())
        this.openSettings = { Settings.showDialog(it, "AniArsiv Ayarları", AniArsivProvider.ROWS) }
    }
}
