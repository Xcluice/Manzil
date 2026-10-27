package com.xcluice.manzil

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val total = 20
    private val audioUrl = "https://manzildua.pk/wp-content/uploads/2025/12/ManzilDua.mp3"
    private val cache = LruCache<Int, Bitmap>(6)
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var curl: CurlView
    private lateinit var counter: TextView
    private lateinit var banner: TextView
    private lateinit var syncBar: TextView
    private lateinit var progFill: View
    private lateinit var progRest: View
    private lateinit var playBtn: PlayButton
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var speedLabel: TextView
    private var pending: Updater.Info? = null

    // audio
    private var mp: MediaPlayer? = null
    private var timings = FloatArray(0)
    private var syncing = false
    private val stamps = FloatArray(total) { -1f }
    private var autoFlip = false
    private var seeking = false
    private var speed = 1f

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

        loadTimings()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        syncBar = tv("", 13f, bg)
        syncBar.gravity = Gravity.CENTER
        syncBar.setBackgroundColor(fg)
        syncBar.visibility = View.GONE
        syncBar.setOnClickListener { finishSync() }
        root.addView(syncBar, LinearLayout.LayoutParams(-1, -2))

        banner = tv("Update available  ·  tap to get it", 13f, bg)
        banner.gravity = Gravity.CENTER
        banner.setBackgroundColor(fg)
        banner.visibility = View.GONE
        banner.setOnClickListener { showUpdateDialog() }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))

        curl = CurlView(this)
        curl.count = total
        curl.loader = { load(it) }
        curl.invert = prefs().getBoolean("invert", night)
        curl.onIndex = { i -> onPage(i) }
        root.addView(curl, LinearLayout.LayoutParams(-1, 0, 1f))

        // audio row
        val audio = LinearLayout(this)
        audio.orientation = LinearLayout.HORIZONTAL
        audio.gravity = Gravity.CENTER_VERTICAL
        audio.setPadding(dp(18), dp(6), dp(12), 0)
        playBtn = PlayButton(this)
        playBtn.tint = fg
        playBtn.setOnClickListener { if (playBtn.playing) pauseAudio() else playAudio() }
        audio.addView(playBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        seek = SeekBar(this)
        seek.max = 1000
        seek.progressTintList = ColorStateList.valueOf(fg)
        seek.thumbTintList = ColorStateList.valueOf(fg)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x55888888)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) timeLabel.text = "${fmt(p * 100)} / ${fmt(mp?.duration ?: 0)}"
            }
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                seeking = false
                val m = mp ?: return
                m.seekTo(s.progress * 100)
                if (hasTimings() && !syncing) {
                    val t = pageAt(s.progress / 10f)
                    if (t != curl.index) { autoFlip = true; curl.jumpTo(t) }
                }
            }
        })
        audio.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        timeLabel = TextView(this)
        timeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        timeLabel.setTextColor(mut)
        timeLabel.text = "0:00 / --:--"
        timeLabel.setPadding(dp(6), 0, dp(4), 0)
        audio.addView(timeLabel, LinearLayout.LayoutParams(-2, -2))
        speedLabel = TextView(this)
        speedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        speedLabel.setTextColor(fg)
        speedLabel.text = "1x"
        speedLabel.setPadding(dp(8), dp(10), dp(8), dp(10))
        speedLabel.setOnClickListener { cycleSpeed() }
        if (Build.VERSION.SDK_INT >= 23) audio.addView(speedLabel, LinearLayout.LayoutParams(-2, -2))
        root.addView(audio, LinearLayout.LayoutParams(-1, -2))

        // page progress line
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
        bar.setPadding(dp(12), dp(4), dp(12), dp(14))
        val go = pill("Go to") { askPage() }
        val sync = pill("Sync") { syncMenu() }
        val nightBtn = pill(if (curl.invert) "Day" else "Night") {}
        nightBtn.setOnClickListener {
            curl.invert = !curl.invert
            nightBtn.text = if (curl.invert) "Day" else "Night"
            prefs().edit().putBoolean("invert", curl.invert).apply()
        }
        val upd = pill("Updates") { checkUpdate(true) }
        for (p in listOf(go, sync, nightBtn, upd)) {
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            bar.addView(p, lp)
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        curl.jumpTo(prefs().getInt("page", 0))
        handler.post(ticker)
        checkUpdate(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        exec.shutdown()
        super.onDestroy()
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.setTextColor(fg)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(4), dp(12), dp(4), dp(12))
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- page changes
    private fun onPage(i: Int) {
        counter.text = "${i + 1}  /  $total"
        val lp = progFill.layoutParams as LinearLayout.LayoutParams
        lp.weight = (i + 1).toFloat()
        progFill.layoutParams = lp
        val lp2 = progRest.layoutParams as LinearLayout.LayoutParams
        lp2.weight = (total - i - 1).toFloat()
        progRest.layoutParams = lp2
        prefs().edit().putInt("page", i).apply()
        exec.execute { for (k in i - 1..i + 2) load(k) }

        if (autoFlip) { autoFlip = false; return }
        if (syncing) { stamp(i); return }
        val m = mp
        if (m != null && m.isPlaying && hasTimings()) m.seekTo((timings[i] * 1000).toInt())
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

    // ---- timings
    private fun parseTimings(s: String): FloatArray {
        val parts = s.split(',', ' ', '\n', '\r', '\t').filter { it.isNotBlank() }
        if (parts.size != total) return FloatArray(0)
        val a = FloatArray(total)
        for (i in parts.indices) a[i] = parts[i].toFloatOrNull() ?: return FloatArray(0)
        for (i in 1 until total) if (a[i] <= a[i - 1]) return FloatArray(0)
        return a
    }

    private fun loadTimings() {
        val p = prefs().getString("stamps", null)
        var t = if (p != null) parseTimings(p) else FloatArray(0)
        if (t.isEmpty()) {
            t = try {
                parseTimings(assets.open("timings.txt").bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                FloatArray(0)
            }
        }
        timings = t
    }

    private fun hasTimings() = timings.size == total

    private fun pageAt(sec: Float): Int {
        var r = 0
        for (i in timings.indices) if (timings[i] <= sec) r = i
        return r
    }

    private fun timingsText() = timings.joinToString(",") { "%.1f".format(Locale.US, it) }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    // ---- audio
    private fun adopt(m: MediaPlayer) {
        mp = m
        m.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        m.setOnCompletionListener { playBtn.playing = false }
        seek.max = m.duration / 100
        timeLabel.text = "0:00 / ${fmt(m.duration)}"
    }

    /** Bundled audio first, then a previously downloaded copy. */
    private fun openLocal(): Boolean {
        val m = MediaPlayer()
        try {
            try {
                val afd = assets.openFd("audio.mp3")
                m.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            } catch (e: IOException) {
                val f = File(filesDir, "audio.mp3")
                if (!f.exists() || f.length() < 500000) {
                    m.release()
                    return false
                }
                m.setDataSource(f.absolutePath)
            }
            m.prepare()
            adopt(m)
            return true
        } catch (e: Exception) {
            try { m.release() } catch (_: Exception) {}
            return false
        }
    }

    private fun ensureAudio(go: () -> Unit) {
        if (mp != null) { go(); return }
        if (openLocal()) { go(); return }
        AlertDialog.Builder(this)
            .setTitle("Download audio")
            .setMessage("The recitation (about 50 MB) isn't inside the app yet. Download it once now?")
            .setPositiveButton("Download") { _, _ -> downloadAudio(go) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAudio(go: () -> Unit) {
        val dlg = AlertDialog.Builder(this).setTitle("Downloading audio").setMessage("0%").setCancelable(false).create()
        dlg.show()
        Thread {
            try {
                val part = File(filesDir, "audio.part")
                val c = URL(audioUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                val len = c.contentLength.toLong()
                c.inputStream.use { ins ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(32768)
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
                                    runOnUiThread { dlg.setMessage("$pct%") }
                                }
                            }
                        }
                    }
                }
                part.renameTo(File(filesDir, "audio.mp3"))
                runOnUiThread {
                    dlg.dismiss()
                    if (openLocal()) go() else toast("Couldn't open the audio")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Download failed. Check your internet.")
                }
            }
        }.start()
    }

    private fun playAudio() {
        ensureAudio {
            val m = mp ?: return@ensureAudio
            if (hasTimings() && !syncing) {
                val s = (timings[curl.index] * 1000).toInt()
                val e = if (curl.index + 1 < total) (timings[curl.index + 1] * 1000).toInt() else m.duration
                val p = m.currentPosition
                if (p < s || p >= e) m.seekTo(s)
            }
            m.start()
            applySpeed()
            playBtn.playing = true
        }
    }

    private fun pauseAudio() {
        try { mp?.pause() } catch (_: Exception) {}
        playBtn.playing = false
    }

    private fun applySpeed() {
        if (Build.VERSION.SDK_INT < 23) return
        val m = mp ?: return
        try { m.playbackParams = m.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    private fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 0.75f
            else -> 1f
        }
        speedLabel.text = speed.toString().trimEnd('0').trimEnd('.') + "x"
        if (mp?.isPlaying == true) applySpeed()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 200)
        }
    }

    private fun tick() {
        val m = mp ?: return
        val pos = try { m.currentPosition } catch (e: Exception) { return }
        if (!seeking) {
            seek.progress = pos / 100
            timeLabel.text = "${fmt(pos)} / ${fmt(m.duration)}"
        }
        playBtn.playing = m.isPlaying
        if (m.isPlaying && !syncing && !seeking && hasTimings() && !curl.busy) {
            val t = pageAt(pos / 1000f)
            if (t != curl.index) {
                autoFlip = true
                if (t == curl.index + 1) {
                    if (!curl.flipNext()) autoFlip = false
                } else {
                    curl.jumpTo(t)
                }
            }
        }
    }

    // ---- sync mode
    private fun syncMenu() {
        if (!hasTimings()) { beginSync(); return }
        AlertDialog.Builder(this)
            .setTitle("Audio sync")
            .setItems(arrayOf("Sync again", "Copy timings")) { _, w -> if (w == 0) beginSync() else showExport() }
            .show()
    }

    private fun beginSync() {
        ensureAudio {
            AlertDialog.Builder(this)
                .setTitle("Sync audio to pages")
                .setMessage("The audio starts from the beginning on page 1. Turn each page when the recitation reaches it. Made a mistake? Go back a page and turn forward again at the right moment. When the recitation ends, tap the strip at the top.")
                .setPositiveButton("Start") { _, _ -> startSync() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startSync() {
        val m = mp ?: return
        syncing = true
        for (i in 0 until total) stamps[i] = -1f
        m.pause()
        m.seekTo(0)
        curl.jumpTo(0)
        stamps[0] = 0f
        syncBar.visibility = View.VISIBLE
        syncText(0)
        m.start()
        applySpeed()
        playBtn.playing = true
    }

    private fun syncText(i: Int) {
        syncBar.text = "SYNC  ·  page ${i + 1} of $total  ·  tap here when finished"
    }

    private fun stamp(i: Int) {
        val pos = (mp?.currentPosition ?: 0) / 1000f
        var t = if (i == 0) 0f else maxOf(0f, pos - 0.6f)
        if (i > 0 && stamps[i - 1] >= 0f) t = maxOf(t, stamps[i - 1] + 0.5f)
        stamps[i] = t
        for (j in i + 1 until total) stamps[j] = -1f
        syncText(i)
    }

    private fun finishSync() {
        if (!syncing) return
        val done = stamps.count { it >= 0f }
        if (done < total) {
            AlertDialog.Builder(this)
                .setTitle("Not finished")
                .setMessage("Only $done of $total pages are marked. Keep going until the last page.")
                .setPositiveButton("Keep syncing", null)
                .setNegativeButton("Discard") { _, _ -> endSync() }
                .show()
            return
        }
        endSync()
        timings = stamps.copyOf()
        prefs().edit().putString("stamps", timingsText()).apply()
        showExport()
    }

    private fun endSync() {
        syncing = false
        syncBar.visibility = View.GONE
        pauseAudio()
    }

    private fun showExport() {
        val txt = timingsText()
        AlertDialog.Builder(this)
            .setTitle("Timings saved")
            .setMessage("Copy this and send it to Claude. It gets built into the app, so it survives uninstalls.\n\n$txt")
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("timings", txt))
                toast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- updates (GitHub releases of this app)
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
        val dlg = AlertDialog.Builder(this).setTitle("Updating").setMessage("Downloading… 0%").setCancelable(false).create()
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
                                    runOnUiThread { dlg.setMessage("Downloading… $pct%") }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
                runOnUiThread { dlg.setMessage("Installing…") }
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
