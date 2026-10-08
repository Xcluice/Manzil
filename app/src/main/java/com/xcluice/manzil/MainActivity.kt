package com.xcluice.manzil

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val total = 20
    private val cache = LruCache<Int, Bitmap>(6)
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var curl: CurlView
    private lateinit var counter: TextView
    private lateinit var banner: TextView
    private lateinit var progFill: View
    private lateinit var progRest: View
    private var pending: Updater.Info? = null

    private var night = false
    private var bg = 0
    private var fg = 0
    private val mut = 0xFF888888.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, sp: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.setPadding(dp(18), dp(12), dp(18), dp(12))
        return t
    }

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        bg = if (night) 0xFF0B0B0B.toInt() else 0xFFF2F2F2.toInt()
        fg = if (night) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!night) {
            var f = window.decorView.systemUiVisibility
            if (Build.VERSION.SDK_INT >= 23) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        banner = tv("Update available  \u00b7  tap to get it", 13f, bg)
        banner.gravity = Gravity.CENTER
        banner.setBackgroundColor(fg)
        banner.visibility = View.GONE
        banner.setOnClickListener { showUpdateDialog() }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))

        curl = CurlView(this)
        curl.count = total
        curl.loader = { load(it) }
        curl.invert = prefs().getBoolean("invert", night)
        curl.onIndex = { i ->
            counter.text = "${i + 1}  /  $total"
            val lp = progFill.layoutParams as LinearLayout.LayoutParams
            lp.weight = (i + 1).toFloat()
            progFill.layoutParams = lp
            val lp2 = progRest.layoutParams as LinearLayout.LayoutParams
            lp2.weight = (total - i - 1).toFloat()
            progRest.layoutParams = lp2
            prefs().edit().putInt("page", i).apply()
            exec.execute { for (k in i - 1..i + 2) load(k) }
        }
        root.addView(curl, LinearLayout.LayoutParams(-1, 0, 1f))

        // progress line
        val prog = LinearLayout(this)
        prog.orientation = LinearLayout.HORIZONTAL
        progFill = View(this)
        progFill.setBackgroundColor(fg)
        progRest = View(this)
        progRest.setBackgroundColor(0x33888888)
        prog.addView(progFill, LinearLayout.LayoutParams(0, dp(2), 1f))
        prog.addView(progRest, LinearLayout.LayoutParams(0, dp(2), 19f))
        val pl = LinearLayout.LayoutParams(-1, dp(2))
        pl.setMargins(dp(22), dp(6), dp(22), 0)
        root.addView(prog, pl)

        counter = TextView(this)
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        counter.setTextColor(fg)
        counter.gravity = Gravity.CENTER
        counter.setPadding(0, dp(10), 0, dp(6))
        counter.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        root.addView(counter, LinearLayout.LayoutParams(-1, -2))

        // actions
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(16), dp(4), dp(16), dp(14))
        val go = pill("Go to") { askPage() }
        val nightBtn = pill(if (curl.invert) "Day" else "Night") {}
        nightBtn.setOnClickListener {
            curl.invert = !curl.invert
            nightBtn.text = if (curl.invert) "Day" else "Night"
            prefs().edit().putBoolean("invert", curl.invert).apply()
        }
        val upd = pill("Updates") { checkUpdate(true) }
        for (p in listOf(go, nightBtn, upd)) {
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(6), 0, dp(6), 0)
            bar.addView(p, lp)
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        curl.jumpTo(prefs().getInt("page", 0))
        checkUpdate(false)
    }

    private fun prefs() = getSharedPreferences("manzil", MODE_PRIVATE)

    private fun line(): View {
        val v = View(this)
        v.setBackgroundColor(0x33888888)
        return v
    }

    private fun pill(label: String, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.setTextColor(fg)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(12), dp(8), dp(12))
        val g = GradientDrawable()
        g.setColor(Color.TRANSPARENT)
        g.setStroke(dp(1), 0x88888888.toInt())
        g.cornerRadius = dp(24).toFloat()
        t.background = g
        t.setOnClickListener { click() }
        return t
    }

    private fun load(i: Int): Bitmap? {
        if (i < 0 || i >= total) return null
        cache.get(i)?.let { return it }
        val bm = try {
            assets.open("p%02d.jpg".format(i + 1)).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bm != null) cache.put(i, bm)
        return bm
    }

    private fun askPage() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1 - $total"
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(et)
            .setPositiveButton("Go") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v != null && v in 1..total) curl.jumpTo(v - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- updates (GitHub releases of this app)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun checkUpdate(manual: Boolean) {
        Thread {
            try {
                val info = Updater.fetch()
                val cur = Updater.installed(this)
                runOnUiThread {
                    if (info != null && info.build > cur) {
                        pending = info
                        banner.visibility = View.VISIBLE
                        if (manual) showUpdateDialog()
                    } else if (manual) {
                        toast("You're up to date (build $cur)")
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { toast("Couldn't check for updates") }
            }
        }.start()
    }

    private fun showUpdateDialog() {
        val info = pending ?: return
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("Build ${info.build} is ready. It downloads here and installs over this one.")
            .setPositiveButton("Update now") { _, _ -> installUpdate(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun installUpdate(info: Updater.Info) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow installing updates")
                .setMessage("Android needs your OK once. Turn on \"Allow from this source\", then come back and tap Update now again.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val dlg = AlertDialog.Builder(this).setTitle("Updating").setMessage("Downloading\u2026 0%").setCancelable(false).create()
        dlg.show()
        Thread {
            var session: PackageInstaller.Session? = null
            try {
                val inst = packageManager.packageInstaller
                val sp = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = inst.createSession(sp)
                val s = inst.openSession(id)
                session = s
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                val len = c.contentLength.toLong()
                c.inputStream.use { ins ->
                    s.openWrite("update.apk", 0, if (len > 0) len else -1).use { out ->
                        val buf = ByteArray(16384)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (len > 0) {
                                val pct = (done * 100 / len).toInt()
                                if (pct != last) {
                                    last = pct
                                    runOnUiThread { dlg.setMessage("Downloading\u2026 $pct%") }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
                runOnUiThread { dlg.setMessage("Installing\u2026") }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(this, 7, Intent(this, InstallReceiver::class.java), flags)
                s.commit(pi.intentSender)
                s.close()
                runOnUiThread { dlg.dismiss() }
            } catch (e: Exception) {
                try { session?.abandon() } catch (_: Exception) {}
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, "Update failed. Check your internet and try again.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
