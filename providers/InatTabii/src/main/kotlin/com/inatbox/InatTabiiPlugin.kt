package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatTabiiPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_W9cS1zuzIl3eLwopo6DWoRtH" }
        registerMainAPI(InatTabiiProvider())
        openSettings = { Settings.showDialog(it, "InatBox Tabii", listOf(cfg.name)) }
    }
}
