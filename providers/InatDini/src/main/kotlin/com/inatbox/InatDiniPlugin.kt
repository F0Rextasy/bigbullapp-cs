package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatDiniPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_1p78-p683RKRQmhQj_bhfaHO" }
        registerMainAPI(InatDiniProvider())
        openSettings = { Settings.showDialog(it, "InatBox Dini", listOf(cfg.name)) }
    }
}
