package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatGainPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_jz59NDtYLsawgRseigIswAEf" }
        registerMainAPI(InatPlatformProvider(cfg))
        openSettings = { Settings.showDialog(it, "InatBox Gain", listOf(cfg.name)) }
    }
}
