package io.github.rareities.protonbridge.Items

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import org.json.JSONObject

@Serializable
data class Filter(var id: Long) {
    @JsonNames("name") var title = ""

    private var filters = ""

    override fun toString(): String {
        return "$title: $filters"
    }

    companion object {
        var TABLE_NAME = "filter_table"
        var COLUMN_NAME_ID = "filter_id"
        var COLUMN_NAME_TITLE = "filter_title"
        var COLUMN_NAME_FILTERS = "filter_filters"

        fun fromString(json: String): Filter {
            return Json.decodeFromString(json)
        }
    }

    fun asJSON(): JSONObject {
        return JSONObject(Json.encodeToString(this))
    }

    fun setFiltersRaw(filters: String)
    {
        this.filters = filters;
    }
    fun getFiltersRaw(): String
    {
        return filters;
    }
    fun getFilters(): ArrayList<FilterEntry> {
        val result = arrayListOf<FilterEntry>()
        for (raw in filters.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            require(line.startsWith("+") || line.startsWith("-")) { "Each saved filter must start with + or -" }
            val type = if (line.startsWith("+")) FilterEntry.FILTER_INCLUDE else FilterEntry.FILTER_EXCLUDE
            result.add(FilterEntry(type, io.github.rareities.protonbridge.util.FilterRules.pattern(line.substring(1))))
        }
        return result
    }

    fun setFilters(filterEntries: List<FilterEntry>) {
        filters = filterEntries.joinToString("\n") {
            (if (it.filterType == FilterEntry.FILTER_INCLUDE) "+" else "-") +
                io.github.rareities.protonbridge.util.FilterRules.savedPattern(it)
        }
    }
}
