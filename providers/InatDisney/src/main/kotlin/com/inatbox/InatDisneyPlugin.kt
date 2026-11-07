package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatDisneyPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_nKFKpIsaKwC_hjUOBLcrYJrI" }
        registerMainAPI(InatDisneyProvider())
        openSettings = { Settings.showDialog(it, "InatBox Disney+", listOf(cfg.name)) }
    }
}
