package com.inatbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatAmazonPrimePlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        val cfg = InatCfgRegistry.ALL.first { it.id == "plat_htbHFdgXWa6QAiUxh2m_iFAc" }
        registerMainAPI(InatAmazonPrimeProvider())
        openSettings = { Settings.showDialog(it, "InatBox Amazon Prime", listOf(cfg.name)) }
    }
}
