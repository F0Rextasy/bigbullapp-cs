package com.asyafanatikleri

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsyaFanatikleriPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AsyaFanatikleriProvider())
        this.openSettings = { Settings.showDialog(it, "Asya Fanatikleri Ayarları", AsyaFanatikleriProvider.ROWS) }
    }
}
