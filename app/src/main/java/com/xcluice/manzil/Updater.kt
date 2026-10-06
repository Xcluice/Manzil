package com.xcluice.manzil

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val REPO = "Xcluice/Manzil"

    class Info(val build: Int, val url: String)

    /** Newest published build from GitHub's "latest release", or null. */
    fun fetch(): Info? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.useCaches = false
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Manzil")
        c.setRequestProperty("Cache-Control", "no-cache")
        val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val r = JSONObject(body)
        val tag = r.optString("tag_name")
        if (!tag.startsWith("build-")) return null
        val build = tag.substring(6).toIntOrNull() ?: return null
        val assets = r.optJSONArray("assets") ?: return null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        return if (url == null) null else Info(build, url)
    }

    @Suppress("DEPRECATION")
    fun installed(c: Context): Int = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }
}
