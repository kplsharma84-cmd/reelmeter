package com.reelmeter

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

data class Today(val reels: Int, val sessions: Int, val skipped: Int, val estSec: Long)

object Store {
    const val SESSION_GAP = 5 * 60_000L   // idle time that ends a session
    const val SKIP_MS = 2_000L            // under this on a Reel = "skipped"
    lateinit var p: SharedPreferences
    private lateinit var db: SQLiteDatabase
    private var prevRow = 0L

    fun init(c: Context) {
        if (::p.isInitialized) return
        val a = c.applicationContext
        p = a.getSharedPreferences("rm", 0)
        db = object : SQLiteOpenHelper(a, "rm.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE ev(ts INTEGER, day TEXT, session INTEGER, idx INTEGER, dur INTEGER, action TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }.writableDatabase
    }

    var tracking: Boolean get() = p.getBoolean("tracking", true); set(v) { p.edit().putBoolean("tracking", v).apply() }
    var overlay: Boolean get() = p.getBoolean("overlay", true); set(v) { p.edit().putBoolean("overlay", v).apply() }
    var warn: Boolean get() = p.getBoolean("warn", true); set(v) { p.edit().putBoolean("warn", v).apply() }
    var corner: Int get() = p.getInt("corner", 0); set(v) { p.edit().putInt("corner", v).apply() }

    fun day(t: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    /** Called once per confirmed Reel. Returns the new session count. */
    fun onReel(now: Long): Int {
        val last = p.getLong("sLast", 0)
        var sid = p.getLong("sid", 0)
        var cnt = p.getInt("sCount", 0)
        if (sid == 0L || now - last > SESSION_GAP) {
            sid = now; cnt = 0; prevRow = 0
            p.edit().putLong("sStart", now).apply()
        }
        if (cnt > 0 && prevRow > 0) {
            val d = now - last
            db.execSQL("UPDATE ev SET dur=?, action=? WHERE rowid=?", arrayOf<Any>(d, if (d < SKIP_MS) "skipped" else "watched", prevRow))
        }
        cnt++
        prevRow = db.insert("ev", null, ContentValues().apply {
            put("ts", now); put("day", day(now)); put("session", sid); put("idx", cnt); put("dur", 0); put("action", "watched")
        })
        p.edit().putLong("sid", sid).putInt("sCount", cnt).putLong("sLast", now).apply()
        return cnt
    }

    fun session(): Triple<Int, Long, Long> = Triple(p.getInt("sCount", 0), p.getLong("sStart", 0), p.getLong("sLast", 0))

    fun resetSession() { prevRow = 0; p.edit().putInt("sCount", 0).putLong("sid", 0).putLong("sStart", 0).putLong("sLast", 0).apply() }

    fun today(): Today {
        val d = arrayOf(day())
        var reels = 0; var sess = 0; var skipped = 0; var est = 0L
        db.rawQuery("SELECT COUNT(*), COUNT(DISTINCT session), SUM(action='skipped') FROM ev WHERE day=?", d).use {
            if (it.moveToFirst()) { reels = it.getInt(0); sess = it.getInt(1); skipped = it.getInt(2) }
        }
        db.rawQuery("SELECT MAX(ts)-MIN(ts) FROM ev WHERE day=? GROUP BY session", d).use { while (it.moveToNext()) est += it.getLong(0) / 1000 }
        return Today(reels, sess, skipped, est)
    }

    fun history(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        db.rawQuery("SELECT day, COUNT(*) FROM ev GROUP BY day ORDER BY day DESC LIMIT 14", null).use { while (it.moveToNext()) out.add(it.getString(0) to it.getInt(1)) }
        return out
    }

    fun clearAll() { resetSession(); db.execSQL("DELETE FROM ev") }
}
