package ca.pkay.rcloneexplorer.bisync

import org.junit.Assert.*
import org.junit.Test

class BisyncAdvancedOptionsTest {
    @Test fun customRowsOverrideTypedControlsExactlyOnce() {
        val options = BisyncOptions(checkers = 4, customOptions = mapOf("checkers" to "8", "bwlimit" to "3M"))
        val args = options.arguments(2)
        assertEquals(1, args.count { it == "--checkers" })
        assertEquals("8", args[args.indexOf("--checkers") + 1])
        assertEquals("3M", args[args.indexOf("--bwlimit") + 1])
        assertEquals("2", args[args.indexOf("--transfers") + 1])
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRepeatedRowsRatherThanHidingOverride() {
        BisyncAdvancedOptions.parseRows("checkers=2\n--checkers=8")
    }
    @Test(expected = IllegalArgumentException::class) fun cannotDisableTlsInCustomOptions() {
        BisyncAdvancedOptions.parseRows("no-check-certificate=true")
    }
    @Test(expected = IllegalArgumentException::class) fun cannotRelocateBaseline() {
        BisyncAdvancedOptions.parseRows("workdir=/tmp/state")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsShellSyntax() {
        BisyncAdvancedOptions.parseRows("checkers=4;rm -rf /tmp")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnboundedMemory() {
        BisyncAdvancedOptions.parseRows("buffer-size=512M")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTimeoutWithoutUnits() {
        BisyncAdvancedOptions.parseRows("timeout=60")
    }
    @Test fun capabilityParserUsesFlagDefinitionsRatherThanProse() {
        val parsed = BisyncCapabilities.parseFlags("Flags:\n      --workdir string path\n  -v, --verbose log\n prose recommends --force\n      --check-access check roots\n")
        assertEquals(setOf("workdir", "verbose", "check-access"), parsed)
    }
}
