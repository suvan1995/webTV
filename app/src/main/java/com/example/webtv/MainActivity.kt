package com.example.webtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Router: first run -> ask for the website, afterwards open it directly. Shows the last crash, if any. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = WebTvApp.crashFile(this)
        val report = try { if (crash.exists()) crash.readText() else null } catch (t: Throwable) { null }
        if (report == null) { route(); return }
        crash.delete()

        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(report.lines().take(45).joinToString("\n"))
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = ScrollView(this).apply { addView(text); isFocusable = true }
        AlertDialog.Builder(this)
            .setTitle("Web TV closed unexpectedly last time")
            .setView(scroll)
            .setPositiveButton("Try again") { _, _ -> route() }
            .setNeutralButton(if (Prefs.safeMode(this)) "Leave safe mode" else "Safe mode") { _, _ ->
                Prefs.setSafeMode(this, !Prefs.safeMode(this)); route()
            }
            .setNegativeButton("Change website") { _, _ ->
                startActivity(Intent(this, SetupActivity::class.java)); finish()
            }
            .setOnCancelListener { route() }
            .show()
    }

    private fun route() {
        val target = if (Prefs.getUrl(this) == null) SetupActivity::class.java else WebActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
