package com.selcuksports

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SelcukSportsPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(SelcukSportsProvider())
        this.openSettings = { Settings.showDialog(it, "SelcukSports Ayarları", SelcukSportsProvider.ROWS) }
    }
}
