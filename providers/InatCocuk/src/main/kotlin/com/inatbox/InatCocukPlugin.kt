package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatCocukPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_B03_bguqxzvRzyvPeiExd_xO" }
        registerMainAPI(InatCocukProvider())
        openSettings = { Settings.showDialog(it, "InatBox Çocuk", listOf(cfg.name)) }
    }
}
