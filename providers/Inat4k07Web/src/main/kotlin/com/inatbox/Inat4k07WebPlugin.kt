package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Inat4k07WebPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_LILDAcQf-pW0mx2p_ZhFL2XS" }
        registerMainAPI(InatPlatformProvider(cfg))
        openSettings = { Settings.showDialog(it, "InatBox 4K Film 07 | Web", listOf(cfg.name)) }
    }
}
