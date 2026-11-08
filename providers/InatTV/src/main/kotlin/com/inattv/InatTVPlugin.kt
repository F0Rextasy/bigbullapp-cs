package com.inattv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatTVPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(InatTVProvider())
        openSettings = { Settings.showDialog(it, "İnatTV Ayarları", InatTVProvider.ROWS) }
    }
}
