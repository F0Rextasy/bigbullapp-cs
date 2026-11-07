package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatListe2GlbPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_-0X2wFp7VAyL4BiETr3DGzxp" }
        registerMainAPI(InatListe2GlbProvider())
        openSettings = { Settings.showDialog(it, "InatBox Liste 2 - GLB", listOf(cfg.name)) }
    }
}
