package de.schuelken.cloudbridge.updates

import org.json.JSONArray

/** Selects a release suitable for the installed channel, independent of API ordering. */
internal object ReleaseSelector {
    data class Release(val tag: String, val changelog: String)

    private data class Version(val parts: List<String>, val pre: List<String>) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            for (index in 0 until maxOf(parts.size, other.parts.size)) {
                val comparison = ReleaseSelector.compareNumbers(parts.getOrElse(index) { "0" }, other.parts.getOrElse(index) { "0" })
                if (comparison != 0) return comparison
            }
            if (pre.isEmpty()) return if (other.pre.isEmpty()) 0 else 1
            if (other.pre.isEmpty()) return -1
            for (index in 0 until minOf(pre.size, other.pre.size)) {
                val a = pre[index]
                val b = other.pre[index]
                val aNumeric = a.all { it in '0'..'9' }
                val bNumeric = b.all { it in '0'..'9' }
                val comparison = when {
                    aNumeric && bNumeric -> ReleaseSelector.compareNumbers(a, b)
                    aNumeric -> -1
                    bNumeric -> 1
                    else -> a.compareTo(b)
                }
                if (comparison != 0) return comparison
            }
            return pre.size.compareTo(other.pre.size)
        }
    }

    private val versionPattern = Regex("^[vV]?(\\d+(?:\\.\\d+){1,3})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

    private fun parse(value: String): Version? {
        val match = versionPattern.matchEntire(value.trim()) ?: return null
        val numbers = match.groupValues[1].split('.')
        // Android debug builds have a packaging suffix, not a release channel.
        val pre = match.groupValues[2].takeUnless { it.isEmpty() || it.equals("DEBUG", true) }
            ?.split('.')?.takeIf { it.isNotEmpty() } ?: emptyList()
        if (pre.any { it.isEmpty() }) return null
        return Version(numbers, pre)
    }

    private fun compareNumbers(first: String, second: String): Int {
        val a = first.trimStart('0').ifEmpty { "0" }
        val b = second.trimStart('0').ifEmpty { "0" }
        return a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun channel(version: Version): Int = when (version.pre.firstOrNull()?.lowercase()) {
        "alpha", "nightly", "dev" -> 3
        "beta" -> 2
        "rc", "pre", "preview" -> 1
        else -> 0
    }

    private fun allowed(current: Version, offered: Version, flaggedPreRelease: Boolean): Boolean {
        val offerChannel = channel(offered)
        if (offered.pre.isEmpty()) return !flaggedPreRelease
        val installed = channel(current)
        return installed > 0 && offerChannel in 1..installed
    }

    fun newest(currentTag: String, releases: JSONArray): Release? {
        val current = parse(currentTag) ?: return null
        var best: Version? = null
        var selected: Release? = null
        for (index in 0 until minOf(20, releases.length())) {
            val item = releases.optJSONObject(index) ?: continue
            if (item.optBoolean("draft", false)) continue
            val tag = item.optString("tag_name")
            val version = parse(tag) ?: continue
            if (!allowed(current, version, item.optBoolean("prerelease", false))) continue
            if (version <= current || (best != null && version <= best)) continue
            best = version
            selected = Release(tag, item.optString("body").take(8192))
        }
        return selected
    }
}
