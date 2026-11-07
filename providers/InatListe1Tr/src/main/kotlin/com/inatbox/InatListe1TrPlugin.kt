package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatListe1TrPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_hklZHuRuuNFJu_hnQLNFtxDX" }
        registerMainAPI(InatListe1TrProvider())
        openSettings = { Settings.showDialog(it, "InatBox Liste 1 - TR", listOf(cfg.name)) }
    }
}
