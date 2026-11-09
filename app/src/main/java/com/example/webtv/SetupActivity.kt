package com.example.webtv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Panel takes 60% of whatever the screen width is (adapts to any display).
        val panel = findViewById<View>(R.id.panel)
        val lp = panel.layoutParams
        lp.width = (resources.displayMetrics.widthPixels * 0.6f).toInt()
        panel.layoutParams = lp

        val field = findViewById<EditText>(R.id.url)
        val error = findViewById<TextView>(R.id.error)
        val open = findViewById<Button>(R.id.open)
        val chips = findViewById<LinearLayout>(R.id.chips)

        // "Add a site" from the menu starts with an empty box; first run shows the current site.
        val adding = intent.getBooleanExtra("add", false)
        if (!adding) Prefs.getUrl(this)?.let { field.setText(it) }
        field.setSelection(field.text.length)

        // Quick-type buttons so you rarely need the on-screen keyboard for the boring parts.
        fun chip(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 18f
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_button)
                setOnClickListener { action() }
            }
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
            chips.addView(b, p)
        }
        fun append(s: String) { field.append(s); field.setSelection(field.text.length) }
        chip(".com") { append(".com") }
        chip(".net") { append(".net") }
        chip(".org") { append(".org") }
        chip(".tv") { append(".tv") }
        chip("Clear") { field.setText("") }

        fun submit() {
            val url = Prefs.normalize(field.text.toString())
            if (url == null) {
                error.visibility = View.VISIBLE
                error.text = getString(R.string.invalid_url)
                return
            }
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            Prefs.addSite(this, url)
            startActivity(Intent(this, WebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }

        open.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        field.requestFocus()
    }
}
