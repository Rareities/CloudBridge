package io.github.rareities.protonbridge.util

import org.junit.Assert.*
import org.junit.Test

class BisyncTuningTest {
    @Test fun templatesAreConcreteAndPreserveOrderedFilters() {
        for (template in BisyncTuning.templates) {
            val applied = BisyncTuning.apply(template, "--filter=+ one/**\n--filter=- two/**\n--bwlimit=50M\n--checkers=30")
            assertTrue(applied.startsWith("--filter=+ one/**\n--filter=- two/**\n"))
            assertFalse(applied.contains("50M"))
            assertFalse(applied.contains("checkers=30"))
            AdvancedOptions.validateConnectionBudget(applied, template.transfers)
            assertEquals(applied, BisyncTuning.apply(template, applied))
        }
    }

    @Test fun undersizedConnectionBudgetsAreRejectedAfterOverrides() {
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedOptions.validateConnectionBudget("--max-connections=4", 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedOptions.validateConnectionBudget("--checkers=2147483647\n--max-connections=2147483647", 4)
        }
        AdvancedOptions.validateConnectionBudget("--checkers=2\n--max-connections=3\n--check-first", 2)
        AdvancedOptions.validateConnectionBudget("--max-connections=0", 4)
    }

    @Test fun presetsCannotEraseAnUnsafeUnvalidatedFlag() {
        assertThrows(IllegalArgumentException::class.java) {
            BisyncTuning.apply(BisyncTuning.templates.first(), "--force")
        }
    }
}
