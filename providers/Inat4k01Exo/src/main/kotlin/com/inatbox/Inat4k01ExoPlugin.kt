package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Inat4k01ExoPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_OzD_GAgae020FLn469Yovxu6" }
        registerMainAPI(Inat4k01ExoProvider())
        openSettings = { Settings.showDialog(it, "InatBox 4K Film 01 | Exo", listOf(cfg.name)) }
    }
}
