package com.diziboxizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziboxIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziboxIzleProvider())
        this.openSettings = { Settings.showDialog(it, "DiziboxIzle Ayarları") }
    }
}
