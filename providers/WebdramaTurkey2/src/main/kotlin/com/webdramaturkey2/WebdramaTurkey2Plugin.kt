package com.webdramaturkey2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebdramaTurkey2Plugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(WebdramaTurkey2Provider())
        this.openSettings = { Settings.showDialog(it, "WebdramaTurkey2 Ayarları") }
    }
}
