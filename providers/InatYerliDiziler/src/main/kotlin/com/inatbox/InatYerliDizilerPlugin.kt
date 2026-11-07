package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatYerliDizilerPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat__LKyrd8ppH12JNFYVgUX1rJO" }
        registerMainAPI(InatYerliDizilerProvider())
        openSettings = { Settings.showDialog(it, "InatBox Yerli Diziler", listOf(cfg.name)) }
    }
}
