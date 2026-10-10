package ca.pkay.rcloneexplorer.bisync

import android.content.Context
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.FilterEntry
import ca.pkay.rcloneexplorer.Items.Task
import java.io.File
import java.security.MessageDigest

/** Ordered app filter rules become an immutable, private engine filters-file. */
object BisyncFilters {
    fun encode(entries: List<FilterEntry>, marker: String? = null): String {
        require(entries.size <= 256) { "Too many bisync filter rules" }
        val rules = entries.map {
            require(it.filterType == FilterEntry.FILTER_INCLUDE || it.filterType == FilterEntry.FILTER_EXCLUDE) { "Unknown filter rule type" }
            require(it.filter.isNotBlank() && it.filter.length <= 1024 && it.filter.none { c -> c.isISOControl() }) { "Invalid bisync filter pattern" }
            (if (it.filterType == FilterEntry.FILTER_INCLUDE) "+ " else "- ") + it.filter
        }
        // A sentinel must remain visible even when payload filters exclude everything.
        return (listOfNotNull(marker?.let { "+ /$it" }) + rules).joinToString("\n", postfix = "\n")
    }

    fun digest(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun resolve(context: Context, task: Task): String {
        val entries = task.filterId?.let { id ->
            DatabaseHandler(context).use { db ->
                val filter = checkNotNull(db.getFilter(id)) { "Selected bisync filter was deleted; review the job" }
                require(filter.getFiltersRaw().lineSequence().filter { it.isNotEmpty() }.all { it.first() == '+' || it.first() == '-' }) {
                    "Malformed saved bisync filter rules; edit the filter before running"
                }
                filter.getFilters()
            }
        } ?: emptyList()
        return encode(entries, if (task.bisyncOptions.requireAccess) BisyncSession.accessFilename(task) else null)
    }

    internal fun snapshot(directory: File, content: String): File {
        val root = File(directory, "filters").apply { check(isDirectory || mkdirs()) { "Cannot create private filter directory" } }
        val file = File(root, "${digest(content)}.rules")
        if (file.exists()) check(file.readText() == content) { "Filter snapshot integrity failed" }
        else BisyncMetadata.write(file, content)
        return file
    }
}
