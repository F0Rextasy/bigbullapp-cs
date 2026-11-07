package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatSporPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_KDLoyZzhSK8eO3SP7pl-lVah" }
        registerMainAPI(InatSporProvider())
        openSettings = { Settings.showDialog(it, "InatBox Spor", listOf(cfg.name)) }
    }
}
