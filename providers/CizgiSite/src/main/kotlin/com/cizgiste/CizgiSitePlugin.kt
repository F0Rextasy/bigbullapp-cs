package com.cizgiste

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CizgiSitePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(CizgiSiteProvider())
        this.openSettings = { Settings.showDialog(it, "CizgiSite Ayarları", CizgiSiteProvider.ROWS) }
    }
}
