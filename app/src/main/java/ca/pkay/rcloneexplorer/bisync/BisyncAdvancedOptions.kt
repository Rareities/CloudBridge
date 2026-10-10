package ca.pkay.rcloneexplorer.bisync

/** Positive allowlist. Values are single argv entries; no shell or positional syntax exists. */
object BisyncAdvancedOptions {
    private val integers = mapOf("checkers" to 1..16, "transfers" to 1..8, "retries" to 1..10,
        "low-level-retries" to 1..20, "tpslimit" to 1..100, "max-connections" to 1..32,
        "multi-thread-streams" to 0..4, "max-backlog" to 16..10000)
    private val seconds = mapOf("timeout" to 30..3600, "contimeout" to 5..300, "retries-sleep" to 1..60)
    val supportedNames: Set<String> = integers.keys + seconds.keys + setOf("bwlimit", "buffer-size", "disable-http2")

    fun validate(name: String, value: String) {
        require(name in supportedNames) { "Option --$name is unsupported or reserved for safety" }
        require(value.length <= 64 && value.none { it.isISOControl() }) { "Invalid option value" }
        when {
            name in integers -> require(value.toIntOrNull()?.let { it in integers.getValue(name) } == true) { "Invalid --$name range" }
            name in seconds -> require(value.endsWith("s") && (value.dropLast(1).toIntOrNull()?.let { it in seconds.getValue(name) } == true)) { "--$name requires seconds with an s suffix" }
            name == "bwlimit" -> require(value.endsWith("M") && (value.dropLast(1).toIntOrNull()?.let { it in 1..10240 } == true)) { "--bwlimit requires 1–10240 MiB/s, e.g. 8M" }
            name == "buffer-size" -> require(value.endsWith("M") && (value.dropLast(1).toIntOrNull()?.let { it in 0..16 } == true)) { "--buffer-size must be 0M–16M" }
            name == "disable-http2" -> require(value == "true" || value == "false") { "--disable-http2 requires true or false" }
        }
    }

    fun parseRows(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { row ->
            val equals = row.indexOf('=')
            require(equals > 0) { "Each row must be option=value; shell syntax is not accepted" }
            val name = row.substring(0, equals).trim().removePrefix("--")
            val value = row.substring(equals + 1).trim()
            require(name !in map) { "Duplicate custom option --$name" }
            validate(name, value)
            map[name] = value
        }
        return map
    }

    fun merge(base: List<String>, overrides: Map<String, String>): List<String> {
        require(base.size % 2 == 0)
        val values = linkedMapOf<String, String>()
        base.chunked(2).forEach { values[it[0]] = it[1] }
        overrides.forEach { (key, value) -> validate(key, value); values["--$key"] = value }
        return values.flatMap { (key, value) -> listOf(key, value) }
    }
}
