package dev.voer.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class Release(val version: String, val notes: String, val page: String, val apkUrl: String?, val apkName: String?, val sumsUrl: String?, val size: Long)

/** Looks for a newer release on GitHub and installs the APK from it. Updates only install over this app because they are signed with the same key. */
object Updater {
    private const val REPO = "veelvoer/agents"

    private fun get(url: String, timeout: Int = 15_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.setRequestProperty("User-Agent", "agents-app"); c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    fun latest(): Release {
        val c = get("https://api.github.com/repos/$REPO/releases/latest")
        try {
            if (c.responseCode !in 200..299) throw ApiError("GitHub answered ${c.responseCode}.")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.optJSONArray("assets")
            var apk: JSONObject? = null; var sums: JSONObject? = null
            if (assets != null) for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.getString("name").endsWith(".apk")) apk = a; if (a.getString("name") == "SHA256SUMS") sums = a }
            return Release(j.getString("tag_name").removePrefix("v"), j.optString("body"), j.optString("html_url"), apk?.getString("browser_download_url"), apk?.getString("name"), sums?.getString("browser_download_url"), apk?.optLong("size") ?: 0L)
        } catch (e: ApiError) { throw e } catch (e: Exception) { throw ApiError("Couldn't reach GitHub.") } finally { c.disconnect() }
    }

    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        val url = r.apkUrl ?: throw ApiError("This release has no Android file.")
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "agents.apk")
        val c = get(url, 30_000)
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            val md = MessageDigest.getInstance("SHA-256"); var got = 0L
            c.inputStream.use { i -> out.outputStream().use { o -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); got += n; if (total > 0) progress(got.toFloat() / total) } } }
            r.sumsUrl?.let { u ->
                val sc = get(u)
                val text = try { sc.inputStream.bufferedReader().readText() } finally { sc.disconnect() }
                val want = text.lines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size > 1 && it[1].removePrefix("*") == r.apkName }?.get(0)
                val have = md.digest().joinToString("") { "%02x".format(it) }
                if (want == null || want != have) { out.delete(); throw ApiError("The download did not match its checksum, so I kept your current version.") }
            }
            return out
        } catch (e: ApiError) { throw e } catch (e: Exception) { out.delete(); throw ApiError("The download failed. Check your internet and try again.") } finally { c.disconnect() }
    }

    /** Returns false when Android first needs the "install unknown apps" permission for this app (it opens that settings page). */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
