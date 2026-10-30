package com.cnnturk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CNNTurkPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CNNTurkProvider())
        this.openSettings = { Settings.showDialog(it, "CNNTurk Ayarları", CNNTurkProvider.ROWS) }
    }
}
