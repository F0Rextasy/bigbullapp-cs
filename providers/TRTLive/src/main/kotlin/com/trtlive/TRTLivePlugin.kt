package com.trtlive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TRTLivePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(TRTLiveProvider())
        this.openSettings = { Settings.showDialog(it, "TRTLive Ayarları", TRTLiveProvider.ROWS) }
    }
}
