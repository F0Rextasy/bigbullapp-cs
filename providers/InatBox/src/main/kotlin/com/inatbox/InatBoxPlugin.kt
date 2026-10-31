package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** One MainAPI per Inat Baks list; every instance carries its own "Inat — <name>" display name. */
@CloudstreamPlugin
class InatBoxPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        InatProvider.ALL.forEach { registerMainAPI(it) }
        this.openSettings = { Settings.showDialog(it, "InatBox Ayarları", InatCfgRegistry.ALL.map { cfg -> cfg.name }) }
    }
}
