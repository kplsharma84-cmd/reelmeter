package com.reelmeter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class TrackerService : AccessibilityService() {
    companion object { var instance: TrackerService? = null; const val IG = "com.instagram.android" }

    private val h = Handler(Looper.getMainLooper())
    private var box: LinearLayout? = null
    private var num: TextView? = null
    private var plus: TextView? = null
    private var lastSig = ""
    private var pending = ""
    private val confirm = Runnable { lastSig = pending; onReel() }

    override fun onServiceConnected() { Store.init(this); instance = this }
    override fun onInterrupt() {}
    override fun onUnbind(i: Intent?): Boolean { instance = null; hide(); return super.onUnbind(i) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg != IG) {
            // Left Instagram: hide the HUD. Everything else is ignored, never read.
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.contains("systemui") && !pkg.contains("inputmethod") && pkg != packageName) { hide(); reset() }
            return
        }
        if (!Store.tracking) { hide(); return }
        show()
        val root = rootInActiveWindow ?: return
        val (inReels, sig) = scan(root)
        if (!inReels) { reset(); return }
        if (sig.isEmpty() || sig == lastSig) { pending = ""; h.removeCallbacks(confirm); return }
        if (sig != pending) { pending = sig; h.removeCallbacks(confirm); h.postDelayed(confirm, 350) } // debounce
    }

    private fun reset() { lastSig = ""; pending = ""; h.removeCallbacks(confirm) }

    /**
     * Returns (is the Reels viewer on screen, signature of the Reel currently centred).
     * Instagram view IDs change between versions. If counting stops working, inspect the
     * Reels screen with Android Studio's Layout Inspector and adjust the "clips_" matches.
     */
    private fun scan(root: AccessibilityNodeInfo): Pair<Boolean, String> {
        val hgt = resources.displayMetrics.heightPixels
        val parts = ArrayList<String>()
        val r = Rect()
        var inReels = false
        var seen = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || seen++ > 600) return
            val id = n.viewIdResourceName ?: ""
            if (n.isVisibleToUser) {
                if (id.contains("clips_viewer")) inReels = true
                if (id.contains("clips_") && parts.size < 3) {
                    val t = (n.text ?: "").toString()
                    if (t.isNotBlank()) { n.getBoundsInScreen(r); if (r.centerY() in hgt / 5..hgt * 9 / 10) parts.add(t.take(40)) }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return Pair(inReels, parts.joinToString("|"))
    }

    private fun onReel() {
        val n = Store.onReel(System.currentTimeMillis())
        refresh(true)
        if (Store.warn && (n == 50 || n == 100 || n == 200)) {
            val msg = when (n) {
                50 -> "You've watched 50 Reels this session."
                100 -> "100 Reels. That's roughly 30+ minutes of scrolling."
                else -> "200 Reels. Still scrolling? Consider a break."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun refresh(anim: Boolean = false) {
        num?.text = "🎬 ${Store.p.getInt("sCount", 0)}"
        if (anim) {
            plus?.apply { text = "+1"; alpha = 1f; animate().alpha(0f).setDuration(700).start() }
            box?.apply { scaleX = 1.15f; scaleY = 1.15f; animate().scaleX(1f).scaleY(1f).setDuration(300).start() }
        }
    }

    fun rebuild() = hide()   // next Instagram event redraws it with the new settings

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun show() {
        if (box != null || !Store.overlay) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        num = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        plus = TextView(this).apply { textSize = 13f; setTextColor(0xFFF2B933.toInt()); alpha = 0f; setPadding(dp(6), 0, 0, 0) }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x99000000.toInt()) }
            addView(num); addView(plus)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = when (Store.corner) { 0 -> Gravity.TOP or Gravity.END; 1 -> Gravity.TOP or Gravity.START; else -> Gravity.BOTTOM or Gravity.END }
            x = dp(12); y = if (Store.corner < 2) dp(96) else dp(140)
        }
        try { wm.addView(box, lp) } catch (e: Exception) { box = null; return }
        refresh()
    }

    private fun hide() {
        val b = box ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(b) } catch (e: Exception) {}
        box = null; num = null; plus = null
    }
}
