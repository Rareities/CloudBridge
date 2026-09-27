package ca.pkay.rcloneexplorer.Database.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONException;
import org.junit.Test;

public class SharedPreferencesBackupTest {

    @Test
    public void legacyBooleanThemeIsAccepted() throws Exception {
        SharedPreferencesBackup.validate("{\"isDarkTheme\":true}");
    }

    @Test
    public void integralNumericStringAndDecimalThemesAreAccepted() throws Exception {
        SharedPreferencesBackup.validate("{\"isDarkTheme\":1}");
        SharedPreferencesBackup.validate("{\"isDarkTheme\":\"1\"}");
        SharedPreferencesBackup.validate("{\"isDarkTheme\":1.0}");
    }

    @Test
    public void ordinaryIntegralScientificNumbersAreAccepted() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":8.08e3}");
        SharedPreferencesBackup.validate("{\"isDarkTheme\":1.0e0}");
    }

    @Test
    public void integralDecimalProxyPortIsAccepted() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":8080.0}");
    }

    @Test
    public void proxyPortLookupSkipsNestedFields() throws Exception {
        SharedPreferencesBackup.validate(
                "{\"metadata\":{\"proxyPort\":8080.0000000000000001,\"items\":[1,2]},"
                        + "\"proxyPort\":8080.0}");
    }

    @Test(expected = JSONException.class)
    public void invalidProxyPortIsRejectedBeforeEditorCreation() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":70000}");
    }

    @Test(expected = JSONException.class)
    public void fractionalProxyPortIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":8080.5}");
    }

    @Test(expected = JSONException.class)
    public void outOfIntRangeProxyPortIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":4294975376}");
    }

    @Test(expected = JSONException.class)
    public void duplicateProxyPortIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":8080,\"proxyPort\":8081}");
    }

    @Test(expected = JSONException.class)
    public void fractionalProxyPortBeyondDoublePrecisionIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":8080.0000000000000001}");
    }

    @Test(expected = JSONException.class)
    public void escapedProxyPortNameStillUsesExactNumber() throws Exception {
        String escapedName = "{\"proxy" + "\\" + "u0050ort\":8080.0000000000000001}";
        SharedPreferencesBackup.validate(escapedName);
    }

    @Test
    public void hugePositiveNumericExponentIsRejectedDuringPreflight() throws Exception {
        JSONException error = validationError("{\"proxyPort\":1e999999999}");
        assertEquals("Preference numeric value exceeds safe parsing limits", error.getMessage());
    }

    @Test
    public void hugeNegativeNumericExponentIsRejectedDuringPreflight() throws Exception {
        JSONException error = validationError("{\"isDarkTheme\":1e-999999999}");
        assertEquals("Preference numeric value exceeds safe parsing limits", error.getMessage());
    }

    @Test
    public void nearImportLimitNumericTokenIsRejectedDuringPreflight() throws Exception {
        String oversizedNumber = new String(new char[262000]).replace('\0', '9');
        JSONException error = validationError("{\"proxyPort\":" + oversizedNumber + "}");
        assertEquals("Preference numeric value exceeds safe parsing limits", error.getMessage());
    }

    @Test
    public void unknownNestedNumbersEnforceInclusiveTokenAndExponentLimits() throws Exception {
        String maxToken = "0." + new String(new char[125]).replace('\0', '0') + "1";
        SharedPreferencesBackup.validate(
                "{\"unknown\":{\"integer\":" + maxToken
                        + ",\"positiveExponent\":1e128,\"negativeExponent\":-1e-128}}");
    }

    @Test
    public void trailingInputAfterPreferencesObjectIsRejected() throws Exception {
        validationError("{\"proxyHost\":\"localhost\"} trailing");
    }

    @Test
    public void unknownBarewordIsRejectedAsNonJson() throws Exception {
        validationError("{\"unknown\":bareword}");
    }

    @Test
    public void trailingJsonWhitespaceIsAccepted() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":8080} \t\r\n");
    }

    @Test
    public void leadingUnicodeBomIsAcceptedLikeAndroidJsonTokener() throws Exception {
        SharedPreferencesBackup.validate("\uFEFF{\"proxyPort\":8080}");
    }

    @Test
    public void bomAfterLeadingWhitespaceIsNotTreatedAsDocumentBom() throws Exception {
        validationError(" \uFEFF{\"proxyPort\":8080}");
    }

    @Test
    public void repeatedLeadingBomsAreRejected() throws Exception {
        validationError("\uFEFF\uFEFF{\"proxyPort\":8080}");
    }

    @Test
    public void invalidStringEscapeIsRejected() throws Exception {
        validationError("{\"unknown\":\"\\x\"}");
    }

    @Test
    public void leadingZeroAndTrailingCommaAreRejected() throws Exception {
        validationError("{\"unknown\":01}");
        validationError("{\"unknown\":[1,]}");
    }

    @Test
    public void oversizedUnknownNestedNumberIsRejectedDuringPreflight() throws Exception {
        String oversizedNumber = new String(new char[129]).replace('\0', '9');
        JSONException error = validationError("{\"unknown\":[" + oversizedNumber + "]}");
        assertEquals("Preference numeric value exceeds safe parsing limits", error.getMessage());
    }

    @Test
    public void outOfRangeExponentOnUnknownNestedNumberIsRejectedDuringPreflight()
            throws Exception {
        JSONException error = validationError("{\"unknown\":{\"number\":1e129}}");
        assertEquals("Preference numeric value exceeds safe parsing limits", error.getMessage());
    }

    @Test
    public void duplicateTopLevelKeysAreRejectedRegardlessOfFinalType() throws Exception {
        validationError("{\"proxyPort\":8080,\"proxyPort\":null}");
        validationError("{\"isDarkTheme\":1,\"isDarkTheme\":true}");
        validationError("{\"proxyPort\":8080,\"proxyPort\":\"8081\"}");
    }

    @Test
    public void escapedEquivalentTopLevelKeysAreRejected() throws Exception {
        String escapedName = "{\"proxyPort\":8080,\"proxy" + "\\" + "u0050ort\":null}";
        validationError(escapedName);
    }

    @Test
    public void nestingAtLimitIsAccepted() throws Exception {
        SharedPreferencesBackup.validate(nestedUnknownArray(64));
    }

    @Test
    public void nestingAboveLimitIsRejected() throws Exception {
        validationError(nestedUnknownArray(65));
    }

    @Test
    public void nestedStringDelimitersDoNotAffectNestingScan() throws Exception {
        SharedPreferencesBackup.validate(
                "{\"unknown\":{\"items\":[{\"text\":\"quote: \\\" braces { } comma ,\"}]},"
                        + "\"proxyPort\":8080.0}");
    }

    private static String nestedUnknownArray(int levels) {
        StringBuilder json = new StringBuilder("{\"unknown\":");
        for (int i = 0; i < levels; i++) {
            json.append('[');
        }
        json.append('0');
        for (int i = 0; i < levels; i++) {
            json.append(']');
        }
        json.append('}');
        return json.toString();
    }

    @Test(expected = JSONException.class)
    public void fractionalNumericThemeIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"isDarkTheme\":1.5}");
    }

    @Test(expected = JSONException.class)
    public void outOfIntRangeNumericThemeIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"isDarkTheme\":4294967297}");
    }

    @Test(expected = JSONException.class)
    public void invalidTransferCountIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"transfers\":\"not-a-number\"}");
    }

    @Test(expected = JSONException.class)
    public void fractionalTransferCountIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"transfers\":\"4.5\"}");
    }

    @Test(expected = JSONException.class)
    public void outOfIntRangeTransferCountIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"transfers\":\"2147483648\"}");
    }

    @Test(expected = JSONException.class)
    public void stringProxyPortIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":\"8080\"}");
    }

    @Test(expected = JSONException.class)
    public void numericTransferCountIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"transfers\":4}");
    }

    @Test(expected = JSONException.class)
    public void structuredThemeIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"isDarkTheme\":{}}");
    }

    @Test
    public void fractionalNumberErrorDoesNotEchoInput() throws Exception {
        String untrustedNumber = "8080.0000000000000001";
        JSONException error = validationError("{\"proxyPort\":" + untrustedNumber + "}");
        assertFalse(error.getMessage().contains(untrustedNumber));
    }

    private static JSONException validationError(String json) throws Exception {
        try {
            SharedPreferencesBackup.validate(json);
        } catch (JSONException error) {
            return error;
        }
        throw new AssertionError("Expected preferences validation to fail");
    }
}
