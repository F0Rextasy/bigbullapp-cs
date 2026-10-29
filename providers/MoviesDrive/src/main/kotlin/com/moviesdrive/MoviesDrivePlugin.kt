package com.moviesdrive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesDrivePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(MoviesDriveProvider())
        this.openSettings = { Settings.showDialog(it, "MoviesDrive Settings") }
    }
}
