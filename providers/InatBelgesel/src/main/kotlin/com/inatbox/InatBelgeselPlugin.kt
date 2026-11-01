package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatBelgeselPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_IPwgYtD4Iaa-f1mu1G603Cwy" }
        registerMainAPI(InatPlatformProvider(cfg))
        openSettings = { Settings.showDialog(it, "InatBox Belgesel", listOf(cfg.name)) }
    }
}
