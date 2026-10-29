package com.diziyou

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziYouPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziYouProvider())
        this.openSettings = { Settings.showDialog(it, "DiziYou Ayarları", DiziYouProvider.ROWS) }
    }
}
