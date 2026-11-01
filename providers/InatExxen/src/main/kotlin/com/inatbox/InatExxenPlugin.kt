package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatExxenPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_BSwGQS-pp65t7fK5csiC9l2g" }
        registerMainAPI(InatPlatformProvider(cfg))
        openSettings = { Settings.showDialog(it, "InatBox EXXEN", listOf(cfg.name)) }
    }
}
