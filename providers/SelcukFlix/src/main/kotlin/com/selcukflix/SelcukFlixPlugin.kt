package com.selcukflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SelcukFlixPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SelcukFlixProvider())
        this.openSettings = { Settings.showDialog(it, "Selcukflix Ayarları", SelcukFlixProvider.ROWS) }
    }
}
