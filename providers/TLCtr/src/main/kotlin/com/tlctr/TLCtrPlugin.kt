package com.tlctr

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TLCtrPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TLCtrProvider())
        this.openSettings = { Settings.showDialog(it, "TLCtr Ayarları", TLCtrProvider.ROWS) }
    }
}
