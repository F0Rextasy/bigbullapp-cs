package com.diziasia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziasiaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziasiaProvider())
        this.openSettings = { Settings.showDialog(it, "DiziAsia Ayarları", DiziasiaProvider.ROWS) }
    }
}
