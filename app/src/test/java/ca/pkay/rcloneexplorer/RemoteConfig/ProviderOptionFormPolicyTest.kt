package ca.pkay.rcloneexplorer.RemoteConfig

import ca.pkay.rcloneexplorer.rclone.ProviderOption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderOptionFormPolicyTest {
    @Test
    fun parsesRequiredAndHideAsUpstreamConfigProviderMetadata() {
        val option = parseOption(
            """{"Name":"account","Help":"Account name","Default":null,"Hide":2,"Required":true,"Type":"string"}"""
        )

        assertTrue(option.required)
        assertEquals("", option.default)
        assertTrue(option.isHiddenFromConfigurator())
    }

    @Test
    fun hideIsABitmaskAndOnlyConfiguratorBitHidesTheField() {
        val commandLineOnly = option("token", hide = ProviderOption.HIDE_COMMAND_LINE)
        val configurator = option("secret", hide = ProviderOption.HIDE_CONFIGURATOR)
        val both = option("internal", hide = ProviderOption.HIDE_COMMAND_LINE or ProviderOption.HIDE_CONFIGURATOR)

        assertFalse(commandLineOnly.isHiddenFromConfigurator())
        assertTrue(configurator.isHiddenFromConfigurator())
        assertTrue(both.isHiddenFromConfigurator())
    }

    @Test
    fun requiredValidationUsesSavedValuesAndNonEmptyDefaults() {
        val required = option("account", required = true)
        val defaulted = option("region", required = true, default = "auto")
        val withSavedValue = option("endpoint", required = true)

        assertEquals(
            listOf(required),
            ProviderOptionFormPolicy.missingRequiredOptions(
                listOf(required, defaulted, withSavedValue),
                mapOf("endpoint" to "https://example.invalid")
            )
        )
        assertTrue(
            ProviderOptionFormPolicy.missingRequiredOptions(
                listOf(required), mapOf("account" to "  ")
            ).isNotEmpty()
        )
    }

    @Test
    fun hiddenOptionsAreNotPromptedButTheirSavedDataIsUntouched() {
        val hidden = option("internal_token", required = true, hide = ProviderOption.HIDE_CONFIGURATOR)
        val values = linkedMapOf("internal_token" to "already-saved")

        assertTrue(ProviderOptionFormPolicy.missingRequiredOptions(listOf(hidden), values).isEmpty())
        assertFalse(ProviderOptionFormPolicy.isVisible(hidden, showAdvanced = true, filter = ""))
        assertEquals("already-saved", values["internal_token"])
    }

    @Test
    fun searchAndAdvancedVisibilityDoNotChangeSubmitTimeRequiredValidationOrSavedValues() {
        val basic = option("account", required = true)
        val advanced = option("client_secret", required = true, advanced = true)
        val saved = linkedMapOf("account" to "user", "legacy_hidden_option" to "preserved")

        assertTrue(ProviderOptionFormPolicy.isVisible(basic, showAdvanced = false, filter = "acc"))
        assertFalse(ProviderOptionFormPolicy.isVisible(advanced, showAdvanced = false, filter = ""))
        assertEquals(listOf(advanced), ProviderOptionFormPolicy.missingRequiredOptions(listOf(basic, advanced), saved))
        assertEquals("preserved", saved["legacy_hidden_option"])
        assertEquals(listOf(advanced), ProviderOptionFormPolicy.missingRequiredOptions(listOf(basic, advanced), saved))
    }

    @Test
    fun checkboxesDoNotFailRequiredValidationBeforeTheyAreChanged() {
        val requiredCheckbox = option("use_tls", required = true, type = "bool")

        assertTrue(ProviderOptionFormPolicy.missingRequiredOptions(listOf(requiredCheckbox), emptyMap()).isEmpty())
    }

    @Test
    fun remoteNameIsRequiredWhenBlankOrWhitespace() {
        assertTrue(ProviderOptionFormPolicy.isRemoteNameMissing(null))
        assertTrue(ProviderOptionFormPolicy.isRemoteNameMissing("  "))
        assertFalse(ProviderOptionFormPolicy.isRemoteNameMissing("archive"))
    }

    private fun parseOption(json: String): ProviderOption =
        requireNotNull(ProviderOption.newInstance(JSONObject(json)))

    private fun option(
        name: String,
        required: Boolean = false,
        default: String = "",
        hide: Int = 0,
        advanced: Boolean = false,
        type: String = "string"
    ) = ProviderOption().apply {
        this.name = name
        this.required = required
        this.default = default
        this.hide = hide
        this.advanced = advanced
        this.type = type
    }
}
