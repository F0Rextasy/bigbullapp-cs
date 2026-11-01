package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatEbaTvPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_B159Mech2izHkFn7NQD6AlOE" }
        registerMainAPI(InatPlatformProvider(cfg))
        openSettings = { Settings.showDialog(it, "InatBox EBA TV", listOf(cfg.name)) }
    }
}
