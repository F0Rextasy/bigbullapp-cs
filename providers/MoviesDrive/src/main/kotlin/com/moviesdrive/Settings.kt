package com.moviesdrive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Per-plugin preferences: quality cap for the hubcloud mirrors. */
object Settings {
    private const val FILE = "bigbull_moviesdrive"
    private var prefs: SharedPreferences? = null

    private val qualityLabels = arrayOf("Unlimited", "1080p", "720p", "480p")
    private val qualityValues = intArrayOf(0, 1080, 720, 480)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var qualityCap: Int
        get() = prefs?.getInt("quality_cap", 0) ?: 0
        set(value) {
            prefs?.edit()?.putInt("quality_cap", value)?.apply()
        }

    fun qualityAllowed(quality: Int): Boolean {
        val cap = qualityCap
        return cap == 0 || quality <= 0 || quality <= cap
    }

    fun showDialog(context: Context, title: String) {
        val activity = context as? Activity ?: return
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
        }
        layout.addView(TextView(activity).apply { text = "Max quality" })
        val qualitySpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, qualityLabels)
            setSelection(qualityValues.indexOf(qualityCap).coerceAtLeast(0))
        }
        layout.addView(qualitySpinner)

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                qualityCap = qualityValues[qualitySpinner.selectedItemPosition]
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
