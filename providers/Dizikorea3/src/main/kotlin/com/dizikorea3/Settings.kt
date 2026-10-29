package com.dizikorea3

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Per-plugin preferences: quality cap and subtitle language. */
object Settings {
    private const val FILE = "bigbull_dizikorea3"
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

    fun showDialog(context: Context, title: String) {
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

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                qualityCap = qualityValues[qualitySpinner.selectedItemPosition]
                subtitleLang = subtitleValues[subtitleSpinner.selectedItemPosition]
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
