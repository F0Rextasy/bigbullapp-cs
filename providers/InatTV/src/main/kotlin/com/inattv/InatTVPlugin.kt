package com.inattv

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import java.io.File
import java.util.Locale

@CloudstreamPlugin
class InatTVPlugin : Plugin() {
    override fun load(context: Context) {
        appContext = context.applicationContext ?: context
        Settings.init(context)
        purgeLegacyInat(appContext, filename)
        registerMainAPI(InatTVProvider())
        openSettings = { Settings.showDialog(it, "İnatTV Ayarları", InatTVProvider.ROWS) }
        runCatching {
            val handler = Handler(Looper.getMainLooper())
            for (delayMs in longArrayOf(800L, 2500L, 6000L)) {
                handler.postDelayed({ purgeLegacyInat(appContext, filename) }, delayMs)
            }
        }
    }

    companion object {
        @Volatile
        private var appContext: Context? = null

        private fun norm(value: String): String = value
            .replace('İ', 'i')
            .replace('I', 'i')
            .replace('ı', 'i')
            .lowercase(Locale.ROOT)
            .trim()

        private fun isLegacyInatName(raw: String): Boolean {
            val n = norm(raw)
            if (n.isEmpty() || n == "inattv" || n == "inat tv") return false
            return n.startsWith("inat") || n.startsWith("i\u0307nat")
        }

        private fun isLegacyInatFile(fileName: String): Boolean {
            val n = norm(fileName)
            if (!n.endsWith(".cs3") && !n.endsWith(".dex") && !n.endsWith(".odex") && !n.endsWith(".vdex") && !n.endsWith(".art")) return false
            if (n.startsWith("inattv.")) return false
            return n.startsWith("inat")
        }

        fun purgeLegacyInat(context: Context? = appContext, selfPath: String? = null) {
            runCatching {
                val roots = LinkedHashSet<File>()
                context?.filesDir?.let { roots.add(it) }
                selfPath?.takeIf { it.isNotBlank() }?.let { File(it).parentFile }?.let { roots.add(it) }
                for (root in roots) {
                    root.walkTopDown().maxDepth(4).forEach { file ->
                        if (file.isFile && isLegacyInatFile(file.name)) {
                            runCatching { PluginManager.unloadPlugin(file.absolutePath) }
                            runCatching { file.delete() }
                        }
                    }
                }
            }
            runCatching {
                val loaded = PluginManager.getPluginsOnline().toList() + PluginManager.getPluginsLocal().toList()
                for (pd in loaded) {
                    val file = File(pd.filePath)
                    if (isLegacyInatName(pd.internalName) || isLegacyInatFile(file.name)) {
                        runCatching { PluginManager.unloadPlugin(pd.filePath) }
                        runCatching { file.delete() }
                    }
                }
            }
            runCatching {
                val legacyApis = (APIHolder.allProviders.toList() + APIHolder.apis.toList()).distinct().filter { api ->
                    val cls = api.javaClass.name
                    api.name != "İnatTV" && (
                        isLegacyInatName(api.name) ||
                            (cls.startsWith("com.inat") && !cls.startsWith("com.inattv."))
                        )
                }
                if (legacyApis.isNotEmpty()) {
                    for (api in legacyApis) {
                        runCatching { APIHolder.removePluginMapping(api) }
                        runCatching { APIHolder.allProviders.remove(api) }
                    }
                    runCatching {
                        APIHolder.apis = APIHolder.apis.filter { it !in legacyApis }
                    }
                }
            }
            runCatching {
                val ctx = context ?: return@runCatching
                for (prefName in listOf("${ctx.packageName}_preferences", "RebuildPreference")) {
                    val prefs = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    var changed = false
                    for ((k, v) in prefs.all) {
                        if (v is String && isLegacyInatName(v)) {
                            editor.putString(k, "İnatTV")
                            changed = true
                        }
                    }
                    if (changed) editor.apply()
                }
            }
        }
    }
}
