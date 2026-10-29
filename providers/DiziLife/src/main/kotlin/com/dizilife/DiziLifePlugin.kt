package com.dizilife

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziLifePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziLifeProvider())
        this.openSettings = { Settings.showDialog(it, "DiziLife Ayarları", DiziLifeProvider.ROWS) }
    }
}
