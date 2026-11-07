package de.baschek.kaffee.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import de.baschek.kaffee.BuildConfig
import de.baschek.kaffee.core.update.ReleaseParser
import de.baschek.kaffee.core.update.UpdateChecker
import de.baschek.kaffee.core.update.UpdateInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updater for sideloaded installs: looks for a newer `android-v*`
 * release on GitHub, downloads the APK and hands it to the package installer.
 * Android only installs it if it is signed with the same key as this app.
 */
class AppUpdater(private val context: Context, httpClient: HttpClient) {
    private val checker = UpdateChecker(httpClient)

    suspend fun checkForUpdate(): UpdateInfo? = checker.check(BuildConfig.VERSION_NAME)

    /** Downloads the APK into the cache (the published digest is required and verified). */
    suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        require(ReleaseParser.isTrustedDownloadUrl(info.apkUrl)) { "untrusted download url" }
        val expected = ReleaseParser.requireDigest(info)
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "kaffee-update.apk")
        var url = info.apkUrl
        var connection = open(url)
        var redirects = 0
        while (connection.responseCode in 301..303 || connection.responseCode in 307..308) {
            val next = connection.getHeaderField("Location")?.let { URL(URL(url), it).toString() }
            connection.disconnect()
            check(redirects++ < MAX_REDIRECTS) { "too many redirects" }
            require(ReleaseParser.isTrustedDownloadUrl(next)) { "untrusted download url" }
            url = next!!
            connection = open(url)
        }
        try {
            check(connection.responseCode == 200) { "download failed" }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!expected.equals(actual, ignoreCase = true)) {
                target.delete()
                error("checksum mismatch")
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
        }

    /** Returns false if the user first has to allow installs from this app (settings screen is opened). */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
