package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatYerliFilmlerPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_Q9tlKwxINorm6gHWwjkJigM9" }
        registerMainAPI(InatYerliFilmlerProvider())
        openSettings = { Settings.showDialog(it, "InatBox Yerli Filmler", listOf(cfg.name)) }
    }
}
