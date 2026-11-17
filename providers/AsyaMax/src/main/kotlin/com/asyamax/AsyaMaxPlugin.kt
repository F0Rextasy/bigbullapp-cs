package com.asyamax

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaMaxPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaMaxProvider())
        this.openSettings = { Settings.showDialog(it, "AsyaMax Ayarları", AsyaMaxProvider.ROWS.map { row -> row.first }) }
    }
}
