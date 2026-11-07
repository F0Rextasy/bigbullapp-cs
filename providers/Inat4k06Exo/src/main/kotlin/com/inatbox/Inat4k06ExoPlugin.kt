package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Inat4k06ExoPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_a058YtcuPSN0YdqLdc-_b6YU" }
        registerMainAPI(Inat4k06ExoProvider())
        openSettings = { Settings.showDialog(it, "InatBox 4K Film 06 | Exo", listOf(cfg.name)) }
    }
}
