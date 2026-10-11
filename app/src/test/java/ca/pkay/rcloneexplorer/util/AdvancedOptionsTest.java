package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AdvancedOptionsTest {
    @Test public void preservesSpacesUnicodeAndEqualsInsideOneArgument() {
        assertEquals(Arrays.asList("--bwlimit=8M", "--include=folder with spaces/**", "--exclude=\u4f60\u597d=old/**"),
                AdvancedOptions.parse("--bwlimit=8M\n--include=folder with spaces/**\n--exclude=\u4f60\u597d=old/**"));
    }

    @Test public void repeatableFiltersKeepTheirOrder() {
        assertEquals(Arrays.asList("--filter=+ A/**", "--filter=- B/**"), AdvancedOptions.parse("--filter=+ A/**\n--filter=- B/**"));
    }

    @Test public void rejectsOverridesAndSecretsEvenWhenSetFalse() {
        for (String name : Arrays.asList("resync", "resync-mode", "workdir", "config", "force", "require-checksum",
                "ignore-errors", "ignore-size", "delete-excluded", "rc-no-auth", "dump", "password-command",
                "protondrive-password", "protondrive-original-file-size", "protondrive-replace-existing-draft",
                "protondrive-enable-caching", "copy-links", "local-encoding", "metadata-mapper")) {
            assertThrows("Must reject " + name, IllegalArgumentException.class, () -> AdvancedOptions.parse("--" + name + "=false"));
        }
    }

    @Test public void rejectsPositionalsSubcommandsShortcutsAndDuplicateScalars() {
        for (String input : Arrays.asList("delete", "remote:path", "-1", "--", "--bwlimit 8M", "--x=a\u0000b", "--bwlimit=1M\n--bwlimit=9M")) {
            assertThrows(input, IllegalArgumentException.class, () -> AdvancedOptions.parse(input));
        }
    }
}
