package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Inat4k06WebPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_k2jK8cUekQuL1CcWqMAzuE2d" }
        registerMainAPI(Inat4k06WebProvider())
        openSettings = { Settings.showDialog(it, "InatBox 4K Film 06 | Web", listOf(cfg.name)) }
    }
}
