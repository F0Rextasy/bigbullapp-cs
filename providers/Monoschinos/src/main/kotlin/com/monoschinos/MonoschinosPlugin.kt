package com.monoschinos

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MonoschinosPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(MonoschinosProvider())
        this.openSettings = { Settings.showDialog(it, "MonosChinos Ajustes") }
    }
}
