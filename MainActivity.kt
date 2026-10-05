package com.reelmeter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val brand = 0xFF0F4C45.toInt()
    private val ink = 0xFF14211F.toInt()
    private val mute = 0xFF5D6F6B.toInt()
    private lateinit var setup: View
    private lateinit var tvLive: TextView
    private lateinit var tvLiveSub: TextView
    private lateinit var tvToday: TextView
    private lateinit var tvHist: TextView
    private lateinit var btnTrack: Button
    private lateinit var btnPos: Button
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun txt(s: String, size: Float = 15f, bold: Boolean = false, color: Int = ink) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun btn(s: String, f: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { f() } }
    private fun sw(s: String, on: Boolean, f: (Boolean) -> Unit) =
        Switch(this).apply { text = s; isChecked = on; setPadding(0, dp(8), 0, dp(8)); setOnCheckedChangeListener { _, c -> f(c) } }
    private fun card(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(1, 0xFFDBE4E0.toInt()) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        v.forEach { addView(it) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        tvLive = txt("0", 60f, true, brand)
        tvLiveSub = txt("", 14f, false, mute)
        tvToday = txt("", 15f)
        tvHist = txt("", 14f)
        tvHist.typeface = Typeface.MONOSPACE
        btnTrack = btn("") { Store.tracking = !Store.tracking; refresh() }
        btnPos = btn("") { Store.corner = (Store.corner + 1) % 3; TrackerService.instance?.rebuild(); refresh() }
        val en = card(
            txt("Turn on tracking", 17f, true),
            txt("1. Tap the button below and open Installed apps / Downloaded apps.\n2. Select ReelMeter and switch it on.\n\nIf the switch is greyed out: Settings > Apps > ReelMeter > ⋮ menu > Allow restricted settings, then try again.", 14f, false, mute),
            btn("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        setup = en
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(txt("ReelMeter", 28f, true, brand))
            addView(txt("See how much you're actually scrolling.", 14f, false, mute).apply { setPadding(0, 0, 0, dp(14)) })
            addView(en)
            addView(card(txt("LIVE SESSION", 12f, true, mute), tvLive, txt("REELS", 12f, true, mute), tvLiveSub,
                LinearLayout(this@MainActivity).apply { addView(btnTrack); addView(btn("Reset session") { Store.resetSession(); TrackerService.instance?.refresh(); refresh() }) }))
            addView(card(txt("Today", 17f, true), tvToday))
            addView(card(txt("Last 14 days", 17f, true), tvHist))
            addView(card(txt("Settings", 17f, true),
                sw("Show overlay in Instagram", Store.overlay) { Store.overlay = it; TrackerService.instance?.rebuild() },
                sw("Gentle warnings at 50 / 100 / 200 Reels", Store.warn) { Store.warn = it },
                btnPos,
                btn("Delete all data") {
                    AlertDialog.Builder(this@MainActivity).setMessage("Delete all history and the current session?")
                        .setPositiveButton("Delete") { _, _ -> Store.clearAll(); TrackerService.instance?.refresh(); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }))
            addView(txt("All data stays on this phone. This app has no internet permission.", 12f, false, mute))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFFF1F5F3.toInt()); addView(root) })
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun enabled(): Boolean =
        (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")

    private fun fmt(s: Long) = if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m ${s % 60}s"

    private fun refresh() {
        setup.visibility = if (enabled()) View.GONE else View.VISIBLE
        val (cnt, start, last) = Store.session()
        val now = System.currentTimeMillis()
        val active = cnt > 0 && now - last < Store.SESSION_GAP
        val secs = if (cnt == 0) 0L else ((if (active) now else last) - start) / 1000
        tvLive.text = "$cnt"
        tvLiveSub.text = (if (active) "Live · " else if (cnt > 0) "Last session · " else "Open Instagram Reels to start · ") +
            fmt(secs) + " · " + String.format("%.1f", if (secs > 0) cnt * 60.0 / secs else 0.0) + " Reels/min"
        val t = Store.today()
        tvToday.text = "${t.reels} Reels seen\n${fmt(t.estSec)} estimated time\n${t.sessions} sessions\n${t.skipped} skipped (under 2 s)"
        val hist = Store.history()
        val max = (hist.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        tvHist.text = if (hist.isEmpty()) "No history yet." else hist.joinToString("\n") {
            it.first.substring(5) + " " + "█".repeat((it.second * 12 / max).coerceAtLeast(1)) + " " + it.second
        }
        btnTrack.text = if (Store.tracking) "Pause tracking" else "Resume tracking"
        btnPos.text = "Overlay position: " + listOf("Top right", "Top left", "Bottom right")[Store.corner]
    }
}
