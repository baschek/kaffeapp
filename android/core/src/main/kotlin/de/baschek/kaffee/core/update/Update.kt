package de.baschek.kaffee.core.update

import de.baschek.kaffee.core.api.SyncApiException
import de.baschek.kaffee.core.api.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Comparable app version such as `1.4.0` or `1.4.0-beta.2` (a pre-release sorts below its release). */
data class AppVersion(val numbers: List<Int>, val preRelease: String?) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val n = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until n) {
            val c = (numbers.getOrElse(i) { 0 }).compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    companion object {
        private val pattern = Regex("""^v?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.\-]+))?(?:\+.*)?$""")
        fun parse(text: String): AppVersion? {
            val m = pattern.matchEntire(text.trim()) ?: return null
            val nums = m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(nums, m.groupValues[2].ifEmpty { null })
        }
    }
}

data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val notes: String?,
)

object ReleaseParser {
    const val TAG_PREFIX = "android-v"

    /** Newest eligible release strictly newer than [currentVersion], or null. */
    fun newestUpdate(json: String, currentVersion: String, includePreReleases: Boolean = false): UpdateInfo? {
        val current = AppVersion.parse(currentVersion) ?: return null
        val array = try {
            WireJson.parseToJsonElement(json) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return null

        return array.mapNotNull { (it as? JsonObject)?.let(::toCandidate) }
            .filter { (v, info, pre) -> v > current && (includePreReleases || !pre) && info.isNotEmpty() }
            .maxByOrNull { it.first }
            ?.second?.firstOrNull()
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull == "true"

    private fun toCandidate(release: JsonObject): Triple<AppVersion, List<UpdateInfo>, Boolean>? {
        if (bool(release, "draft")) return null
        val tag = str(release, "tag_name") ?: return null
        if (!tag.startsWith(TAG_PREFIX)) return null
        val versionName = tag.removePrefix(TAG_PREFIX)
        val version = AppVersion.parse(versionName) ?: return null
        val asset = (release["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull {
            str(it, "name")?.endsWith(".apk") == true && isTrustedDownloadUrl(str(it, "browser_download_url"))
        }
        val info = asset?.let {
            UpdateInfo(
                tag = tag,
                versionName = versionName,
                apkUrl = str(it, "browser_download_url")!!,
                apkName = str(it, "name")!!,
                sizeBytes = (it["size"] as? JsonPrimitive)?.longOrNull,
                sha256 = str(it, "digest")?.removePrefix("sha256:")?.takeIf { d -> d.length == 64 },
                notes = str(release, "body")?.take(2000),
            )
        }
        return Triple(version, listOfNotNull(info), bool(release, "prerelease"))
    }

    /** Only HTTPS downloads from GitHub hosts, without user info, are accepted. */
    fun isTrustedDownloadUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = try {
            java.net.URI(url)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.userInfo == null &&
            (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    /** The published SHA-256 of the APK; an update without one is never installed. */
    fun requireDigest(info: UpdateInfo): String =
        info.sha256 ?: throw IllegalStateException("Update blocked: the release publishes no SHA-256 digest for the APK")
}

/** Looks up the newest `android-v*` release of a GitHub repository. */
class UpdateChecker(
    private val client: HttpClient,
    private val repo: String = "baschek/kaffeapp",
) {
    suspend fun check(currentVersion: String, includePreReleases: Boolean = false): UpdateInfo? {
        val body = try {
            val response = client.get("https://api.github.com/repos/$repo/releases?per_page=30") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (response.status.value !in 200..299) throw SyncApiException.Http(response.status.value, null)
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncApiException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.Network(e)
        }
        return ReleaseParser.newestUpdate(body, currentVersion, includePreReleases)
    }
}
