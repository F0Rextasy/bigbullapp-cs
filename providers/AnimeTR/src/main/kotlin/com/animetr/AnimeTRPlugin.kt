package com.animetr

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeTRPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(AnimeTRProvider())
        this.openSettings = { Settings.showDialog(it, "AnimeTR Ayarları", AnimeTRProvider.ROWS) }
    }
}
