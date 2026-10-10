package de.schuelken.cloudbridge.updates.workmanager

/** Pure selection policy; never infer chronological order from a commit hash. */
internal object ReleasePolicy {
    data class Release(val tag: String, val body: String = "", val draft: Boolean = false,
                       val prerelease: Boolean = false, val publishedAt: String = "")
    private data class Version(val core: List<Long>, val pre: List<String>) : Comparable<Version> {
        val hashBeta: Boolean get() = pre.size == 2 && pre[0] == "beta" && pre[1].matches(Regex("[0-9a-f]{7,40}"))
        override fun compareTo(other: Version): Int {
            for (i in core.indices) if (core[i] != other.core[i]) return core[i].compareTo(other.core[i])
            if (pre.isEmpty() || other.pre.isEmpty()) return when {
                pre.isEmpty() && other.pre.isEmpty() -> 0
                pre.isEmpty() -> 1
                else -> -1
            }
            for (i in 0 until minOf(pre.size, other.pre.size)) {
                val a = pre[i]; val b = other.pre[i]
                if (a == b) continue
                val an = a.toLongOrNull(); val bn = b.toLongOrNull()
                return when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
            }
            return pre.size.compareTo(other.pre.size)
        }
    }
    private fun parse(tag: String): Version? {
        val normalized = tag.removePrefix("v").removePrefix("V").substringBefore('+')
        val match = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([0-9A-Za-z.-]+))?").matchEntire(normalized) ?: return null
        val core = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
        val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) return null
        return Version(core, pre)
    }
    fun select(current: String, releases: List<Release>): Release? {
        val installed = parse(current) ?: return null
        val allowPreview = installed.pre.firstOrNull() in listOf("alpha", "beta", "rc", "nightly", "dev")
        val currentPublication = releases.firstOrNull { !it.draft && parse(it.tag) == installed }?.publishedAt.orEmpty()
        val eligible = releases.mapNotNull { release ->
            val version = parse(release.tag) ?: return@mapNotNull null
            if (release.draft || (!allowPreview && (release.prerelease || version.pre.isNotEmpty()))) return@mapNotNull null
            val newer = if (installed.hashBeta && version.hashBeta && installed.core == version.core) {
                // Existing beta tags use SHA suffixes. Only a known installed release date proves an upgrade.
                version != installed && currentPublication.isNotEmpty() && release.publishedAt > currentPublication
            } else version > installed
            if (newer) release to version else null
        }
        return eligible.maxWithOrNull { a, b ->
            if (a.second.hashBeta && b.second.hashBeta && a.second.core == b.second.core) {
                a.first.publishedAt.compareTo(b.first.publishedAt)
            } else a.second.compareTo(b.second)
        }?.first
    }
}
