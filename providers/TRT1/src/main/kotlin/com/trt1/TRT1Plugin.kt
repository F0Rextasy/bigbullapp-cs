package com.trt1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TRT1Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TRT1Provider())
        this.openSettings = { Settings.showDialog(it, "TRT1 Ayarları", TRT1Provider.ROWS) }
    }
}
