package dev.ii.tools.update

import org.json.JSONArray
import org.json.JSONObject

/** One published GitHub release that is newer than the installed build. */
data class ReleaseInfo(
    val tagName: String,
    val version: SemVer,
    val name: String,
    val publishedAt: String,
    val htmlUrl: String,
    val body: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** Lowercase hex SHA-256 of the APK from GitHub's asset `digest`, when GitHub provides one. */
    val apkSha256: String? = null,
)

/** Parses GitHub's `/releases` payload and round-trips cached newer releases through JSON. */
object ReleaseParser {
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun parseGitHubReleases(json: String, currentVersion: SemVer): List<ReleaseInfo> {
        val arr = JSONArray(json)
        val out = ArrayList<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) continue
            val tag = obj.optString("tag_name").trim()
            val ver = SemVer.parse(tag) ?: continue
            if (ver <= currentVersion) continue

            val name = obj.optString("name").trim().ifEmpty { tag }
            val publishedAt = obj.optString("published_at").trim()
                .ifEmpty { obj.optString("created_at").trim() }
            val htmlUrl = obj.optString("html_url").trim().takeIf { it.startsWith("https://") }.orEmpty()
            val body = obj.optString("body")
            val apk = pickApkAsset(obj.optJSONArray("assets"))

            out += ReleaseInfo(
                tagName = tag,
                version = ver,
                name = name,
                publishedAt = publishedAt,
                htmlUrl = htmlUrl,
                body = body,
                apkUrl = apk?.url,
                apkName = apk?.name,
                apkSize = apk?.size ?: 0L,
                apkSha256 = apk?.sha256,
            )
        }
        return out.sortedByDescending { it.version }
    }

    fun toCacheJson(releases: List<ReleaseInfo>): String {
        val arr = JSONArray()
        for (r in releases) {
            arr.put(
                JSONObject()
                    .put("tag", r.tagName)
                    .put("name", r.name)
                    .put("publishedAt", r.publishedAt)
                    .put("htmlUrl", r.htmlUrl)
                    .put("body", r.body)
                    .put("apkUrl", r.apkUrl ?: "")
                    .put("apkName", r.apkName ?: "")
                    .put("apkSize", r.apkSize)
                    .put("apkSha256", r.apkSha256 ?: ""),
            )
        }
        return arr.toString()
    }

    fun fromCacheJson(json: String, currentVersion: SemVer): List<ReleaseInfo> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        val out = ArrayList<ReleaseInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val tag = obj.optString("tag")
            val ver = SemVer.parse(tag) ?: continue
            // Re-filter against the running version so installing an update clears stale notices at once.
            if (ver <= currentVersion) continue
            out += ReleaseInfo(
                tagName = tag,
                version = ver,
                name = obj.optString("name").ifEmpty { tag },
                publishedAt = obj.optString("publishedAt"),
                htmlUrl = obj.optString("htmlUrl").takeIf { it.startsWith("https://") }.orEmpty(),
                body = obj.optString("body"),
                apkUrl = obj.optString("apkUrl").takeIf { it.startsWith("https://") },
                apkName = obj.optString("apkName").takeIf { it.isNotEmpty() },
                apkSize = obj.optLong("apkSize", 0L).coerceAtLeast(0L),
                apkSha256 = parseSha256(obj.optString("apkSha256")),
            )
        }
        return out.sortedByDescending { it.version }
    }

    private data class ApkAsset(val url: String, val name: String, val size: Long, val sha256: String?)

    /** Accepts `sha256:<hex>` (GitHub's format) or bare hex; anything else is treated as absent. */
    private fun parseSha256(raw: String): String? =
        raw.trim().removePrefix("sha256:").lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private fun pickApkAsset(assets: JSONArray?): ApkAsset? {
        if (assets == null) return null
        var fallback: ApkAsset? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url").trim()
            if (!url.startsWith("https://")) continue
            val name = a.optString("name").trim()
            val contentType = a.optString("content_type").trim()
            val isApk = name.endsWith(".apk", ignoreCase = true) || contentType.equals(APK_MIME, ignoreCase = true)
            if (!isApk) continue
            val size = a.optLong("size", 0L).coerceAtLeast(0L)
            val candidate = ApkAsset(url, name, size, parseSha256(a.optString("digest")))
            if (name.contains("ii-tools", ignoreCase = true)) return candidate
            if (fallback == null) fallback = candidate
        }
        return fallback
    }
}
