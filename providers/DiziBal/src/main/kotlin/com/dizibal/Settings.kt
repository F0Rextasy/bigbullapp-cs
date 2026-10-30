package com.dizibal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Per-plugin preferences: quality cap, subtitle language and homepage rows. */
object Settings {
    private const val FILE = "bigbull_dizibal"
    private const val ROW_KEY = "rows"
    private var prefs: SharedPreferences? = null

    private val qualityLabels = arrayOf("Sınırsız", "1080p", "720p", "480p", "360p")
    private val qualityValues = intArrayOf(0, 1080, 720, 480, 360)
    private val subtitleLabels = arrayOf("Tümü", "Türkçe", "İngilizce", "Kapalı")
    private val subtitleValues = arrayOf("all", "tr", "en", "off")

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var qualityCap: Int
        get() = prefs?.getInt("quality_cap", 0) ?: 0
        set(value) {
            prefs?.edit()?.putInt("quality_cap", value)?.apply()
        }

    var subtitleLang: String
        get() = prefs?.getString("subtitle_lang", "all") ?: "all"
        set(value) {
            prefs?.edit()?.putString("subtitle_lang", value)?.apply()
        }

    fun qualityAllowed(quality: Int): Boolean {
        val cap = qualityCap
        return cap == 0 || quality <= 0 || quality <= cap
    }

    fun subtitleAllowed(language: String?): Boolean {
        val pref = subtitleLang
        if (pref == "off") return false
        if (pref == "all") return true
        val label = language?.lowercase().orEmpty()
        return when (pref) {
            "tr" -> label.contains("tr") || label.contains("türk") || label.contains("turk")
            "en" -> label.contains("en") || label.contains("ingiliz") || label.contains("english")
            else -> true
        }
    }

    /** Rows never switched off stay visible, so a fresh install shows every category. */
    fun rowEnabled(name: String): Boolean = prefs?.getStringSet(ROW_KEY, null)?.contains(name) != true

    fun setRows(names: Collection<String>, enabled: Set<String>) {
        prefs?.edit()?.putStringSet(ROW_KEY, names.filterNot { it in enabled }.toSet())?.apply()
    }

    fun showDialog(context: Context, title: String, rows: List<String>) {
        val activity = context as? Activity ?: return
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
        }
        layout.addView(TextView(activity).apply { text = "Kalite üst sınırı" })
        val qualitySpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, qualityLabels)
            setSelection(qualityValues.indexOf(qualityCap).coerceAtLeast(0))
        }
        layout.addView(qualitySpinner)
        layout.addView(TextView(activity).apply { text = "Altyazı" })
        val subtitleSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, subtitleLabels)
            setSelection(subtitleValues.indexOf(subtitleLang).coerceAtLeast(0))
        }
        layout.addView(subtitleSpinner)
        layout.addView(TextView(activity).apply { text = "Ana sayfa kategorileri" })
        val checks = rows.map { name ->
            CheckBox(activity).apply {
                text = name
                isChecked = rowEnabled(name)
            }.also { layout.addView(it) }
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Kaydet") { _, _ ->
                qualityCap = qualityValues[qualitySpinner.selectedItemPosition]
                subtitleLang = subtitleValues[subtitleSpinner.selectedItemPosition]
                setRows(rows, checks.filter { it.isChecked }.map { it.text.toString() }.toSet())
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
