package sgnv.anubis.app.update

data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val releaseNotes: String,
) {
    val isUpdateAvailable: Boolean get() = compareVersions(latestVersion, currentVersion) > 0

    companion object {
        /**
         * Semver-style version comparison. Returns 1 / 0 / -1.
         *
         * Cases this needs to get right:
         *  - "0.1.5"          > "0.1.4.1"          (numeric)
         *  - "0.1.5"          > "0.1.5-beta.2"     (release beats pre-release)
         *  - "0.1.5-beta.2"   > "0.1.4.1"          (numeric — pre-release of next minor)
         *  - "0.1.5-beta.3"   > "0.1.5-beta.2"     (lexicographic on suffix)
         *
         * The release-beats-pre-release rule is critical for the upgrade path from
         * 0.1.5-beta.X to 0.1.5 stable; the previous implementation stripped suffixes
         * before comparing and returned 0 for that pair, hiding the update.
         */
        fun compareVersions(a: String, b: String): Int {
            fun numericParts(v: String): List<Int> =
                v.trimStart('v').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

            fun preReleaseSuffix(v: String): String? {
                val trimmed = v.trimStart('v')
                val dash = trimmed.indexOf('-')
                return if (dash >= 0) trimmed.substring(dash + 1) else null
            }

            val aParts = numericParts(a)
            val bParts = numericParts(b)
            val len = maxOf(aParts.size, bParts.size)
            for (i in 0 until len) {
                val ai = aParts.getOrElse(i) { 0 }
                val bi = bParts.getOrElse(i) { 0 }
                if (ai != bi) return ai.compareTo(bi)
            }

            val aPre = preReleaseSuffix(a)
            val bPre = preReleaseSuffix(b)
            return when {
                aPre == null && bPre == null -> 0
                aPre == null -> 1
                bPre == null -> -1
                else -> aPre.compareTo(bPre)
            }
        }
    }
}
