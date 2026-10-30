package com.koreanturk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KoreanTurkPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(KoreanTurkProvider())
        this.openSettings = { Settings.showDialog(it, "KoreanTurk Ayarları", KoreanTurkProvider.ROWS) }
    }
}
