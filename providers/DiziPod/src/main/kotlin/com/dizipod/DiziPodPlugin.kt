package com.dizipod

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziPodPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziPodProvider())
        this.openSettings = { Settings.showDialog(it, "DiziPod Ayarları") }
    }
}
