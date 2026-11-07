package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatSinemaPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_5BtmCy7tc7RE19th4-8ZI645" }
        registerMainAPI(InatSinemaProvider())
        openSettings = { Settings.showDialog(it, "InatBox Sinema", listOf(cfg.name)) }
    }
}
