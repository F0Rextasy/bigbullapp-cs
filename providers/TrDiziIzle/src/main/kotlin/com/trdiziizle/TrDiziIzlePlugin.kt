package com.trdiziizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TrDiziIzlePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TrDiziIzleProvider())
        this.openSettings = { Settings.showDialog(it, "TrDiziIzle Ayarları", TrDiziIzleProvider.ROWS) }
    }
}
