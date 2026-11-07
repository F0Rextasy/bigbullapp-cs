package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatMubiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_pfMNTPEDYNErCM1RafOgADx-" }
        registerMainAPI(InatMubiProvider())
        openSettings = { Settings.showDialog(it, "InatBox Mubi", listOf(cfg.name)) }
    }
}
