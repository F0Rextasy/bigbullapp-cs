package com.canlitv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView

/** Per-plugin preferences: homepage rows. */
object Settings {
    private const val FILE = "bigbull_canlitv"
    private const val ROW_KEY = "rows"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
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
                setRows(rows, checks.filter { it.isChecked }.map { it.text.toString() }.toSet())
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
