package ca.pkay.rcloneexplorer.RemoteConfig

import ca.pkay.rcloneexplorer.rclone.ProviderOption

/** Presentation and submit-time validation rules for the dynamic provider form. */
internal object ProviderOptionFormPolicy {
    fun isVisible(option: ProviderOption, showAdvanced: Boolean, filter: String): Boolean {
        if (option.isHiddenFromConfigurator()) return false
        if (option.advanced && !showAdvanced) return false
        return filter.isBlank() ||
            option.name.contains(filter, ignoreCase = true) ||
            option.help.contains(filter, ignoreCase = true)
    }

    /**
     * Required means non-empty unless rclone provides a non-empty default.
     * Search and the advanced toggle affect presentation only; they must not
     * let submission bypass validation. Configurator-hidden options are not
     * user-editable and are therefore left to rclone's own defaults.
     */
    fun missingRequiredOptions(
        options: Iterable<ProviderOption>,
        values: Map<String, String>
    ): List<ProviderOption> = options.filter { option ->
        if (!option.required || option.isHiddenFromConfigurator() || option.default.isNotBlank()) {
            return@filter false
        }

        // A checkbox always has a concrete false state even when no explicit
        // map value has been written yet (listeners only store changed values).
        if (option.type.equals("bool", ignoreCase = true)) return@filter false

        values[option.name].isNullOrBlank()
    }

    fun isRemoteNameMissing(name: String?): Boolean = name.isNullOrBlank()
}
