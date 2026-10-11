package ca.pkay.rcloneexplorer.util

/** Templates become concrete saved settings. They never change a running task indirectly. */
object BisyncTuning {
    data class Template(val title: String, val transfers: Int, val flags: String)
    val templates = listOf(
        Template("Balanced", 2, "--checkers=4\n--buffer-size=4M\n--tpslimit=10\n--max-connections=7"),
        Template("Low memory", 1, "--checkers=2\n--buffer-size=0M\n--tpslimit=4\n--max-connections=4"),
        Template("Limited bandwidth", 1, "--checkers=2\n--buffer-size=1M\n--tpslimit=2\n--max-connections=4\n--bwlimit=2M")
    )
    private val tuning = setOf("checkers", "buffer-size", "tpslimit", "tpslimit-burst", "max-connections", "bwlimit")

    fun apply(template: Template, currentFlags: String): String {
        val retained = AdvancedOptions.parse(currentFlags).filter {
            it.substringBefore('=').removePrefix("--") !in tuning
        }
        return (retained + AdvancedOptions.parse(template.flags)).joinToString("\n").also {
            AdvancedOptions.validateConnectionBudget(it, template.transfers)
        }
    }
}
