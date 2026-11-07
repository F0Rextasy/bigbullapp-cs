package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Inat4k03ExoPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_Y4HmB7sMjvY66BrS0pt6gGGY" }
        registerMainAPI(Inat4k03ExoProvider())
        openSettings = { Settings.showDialog(it, "InatBox 4K Film 03 | Exo", listOf(cfg.name)) }
    }
}
