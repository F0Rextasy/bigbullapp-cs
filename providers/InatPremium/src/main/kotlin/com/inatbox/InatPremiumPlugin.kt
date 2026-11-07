package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatPremiumPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_XbOO87izu6ge2wsD9dQda4gF" }
        registerMainAPI(InatPremiumProvider())
        openSettings = { Settings.showDialog(it, "InatBox Premium", listOf(cfg.name)) }
    }
}
