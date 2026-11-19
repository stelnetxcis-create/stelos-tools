package dev.ii.tools.update

/**
 * Semantic version parsed from a release tag or versionName (e.g. `v1.2.0`, `1.2.0-rc.1`).
 * Pre-release identifiers follow SemVer 2.0.0 precedence so `1.2.0 > 1.2.0-rc.1 > 1.1.0`.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preRelease == other.preRelease) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        if (preRelease.isNullOrEmpty()) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        fun parse(raw: String): SemVer? {
            val cleaned = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            if (cleaned.isEmpty()) return null
            val dash = cleaned.indexOf('-')
            val core = if (dash >= 0) cleaned.substring(0, dash) else cleaned
            val pre = if (dash >= 0) cleaned.substring(dash + 1).takeIf { it.isNotEmpty() } else null
            val parts = core.split('.')
            if (parts.size !in 2..3) return null
            val nums = parts.map { p ->
                if (p.isEmpty() || !p.all(Char::isDigit)) return null
                p.toIntOrNull() ?: return null
            }
            return SemVer(
                major = nums[0],
                minor = nums[1],
                patch = nums.getOrElse(2) { 0 },
                preRelease = pre,
            )
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val pa = a.split('.')
            val pb = b.split('.')
            val n = minOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa[i]
                val y = pb[i]
                if (x == y) continue
                val xNum = x.toLongOrNull()
                val yNum = y.toLongOrNull()
                return when {
                    xNum != null && yNum != null -> xNum.compareTo(yNum)
                    xNum != null -> -1 // Numeric identifiers always have lower precedence than non-numeric.
                    yNum != null -> 1
                    else -> x.compareTo(y)
                }
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
