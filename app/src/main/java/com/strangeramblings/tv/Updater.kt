package com.strangeramblings.tv

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Self-update: reads https://strangeramblings.com/tv/version.json on launch
 * ({ versionCode, versionName, url }) and offers to install a newer APK.
 * Any failure is silent; the endpoint may not exist yet.
 */
class Updater(private val activity: MainActivity) {

    companion object {
        private const val TAG = "SrTvUpdater"
        const val VERSION_URL = "https://strangeramblings.com/tv/version.json"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    private data class Release(val versionCode: Int, val versionName: String, val url: String)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending: Release? = null
    private var awaitingInstallPermission = false
    private var busy = false

    fun checkOnLaunch() {
        executor.execute {
            val release = try {
                fetchRelease()
            } catch (e: Exception) {
                Log.i(TAG, "update check skipped: ${e.message}")
                null
            } ?: return@execute
            if (release.versionCode > BuildConfig.VERSION_CODE) main.post { offer(release) }
        }
    }

    fun onResume() {
        val release = pending ?: return
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (canInstall()) download(release)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun fetchRelease(): Release? {
        val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SRTV/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val code = json.optInt("versionCode", 0)
            val name = json.optString("versionName", "").ifBlank { "build $code" }
            val url = json.optString("url", "")
            if (code <= 0 || !url.startsWith("https://")) return null
            return Release(code, name, url)
        } finally {
            conn.disconnect()
        }
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun offer(release: Release) {
        if (!alive()) return
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update sr. tv to ${release.versionName}?")
            .setMessage("A newer version of the app is available. You're on ${BuildConfig.VERSION_NAME}.")
            .setPositiveButton("Update") { _, _ -> start(release) }
            .setNegativeButton("Later", null)
            .setOnDismissListener { activity.refocusWebView() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun start(release: Release) {
        pending = release
        if (canInstall()) {
            download(release)
            return
        }
        awaitingInstallPermission = true
        toast("Allow sr. tv to install updates, then press Back")
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        } catch (e: ActivityNotFoundException) {
            awaitingInstallPermission = false
            toast("Turn on \"Install unknown apps\" for sr. tv in Settings")
        }
    }

    @Suppress("DEPRECATION")
    private fun download(release: Release) {
        if (busy) return
        busy = true
        val progress = android.app.ProgressDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert).apply {
            setTitle("Downloading sr. tv ${release.versionName}")
            setMessage("Just a moment...")
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        executor.execute {
            val file = try {
                fetchApk(release)
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                null
            }
            main.post {
                busy = false
                if (alive()) progress.dismiss()
                if (file == null) {
                    toast("Update download failed. Try again later.")
                } else if (alive()) {
                    install(file)
                }
            }
        }
    }

    private fun fetchApk(release: Release): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "sr-tv-${release.versionCode}.apk")
        val partial = File(dir, "${target.name}.part")
        // Redirects are followed within https (GitHub -> its asset CDN).
        val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SRTV/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (!partial.renameTo(target)) throw IllegalStateException("could not finalise download")
        return target
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("This TV has no package installer available")
        }
    }

    private fun toast(message: String) {
        if (alive()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
