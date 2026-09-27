package ca.pkay.rcloneexplorer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RcdExternalFailureTest {

    @Test
    public void jobNotFoundIsTypedWithoutEchoingServerText() {
        RcdExternalFailure failure = RcdExternalFailure.fromResponse("job not found", 404);

        assertEquals(RcdExternalFailure.Kind.JOB_NOT_FOUND, failure.getKind());
        assertEquals("The requested rclone job is no longer available.", failure.getMessage());
    }

    @Test
    public void rejectedAndServerFailuresUseFixedSafeMessages() {
        RcdExternalFailure rejected = RcdExternalFailure.fromResponse(
                "token=secret /private/user/path", 400);
        assertEquals(RcdExternalFailure.Kind.REQUEST_REJECTED, rejected.getKind());
        assertEquals("Rclone rejected the request.", rejected.getMessage());

        RcdExternalFailure uncertain = RcdExternalFailure.fromResponse(
                "password=secret", 503);
        assertEquals(RcdExternalFailure.Kind.SERVER_FAILURE, uncertain.getKind());
        assertTrue(uncertain.getMessage().contains("may have started"));
        assertTrue(uncertain.getMessage().contains("verify remote state"));
    }

    @Test
    public void transportFailuresExplainThatMutationOutcomeMayBeUnknown() {
        RcdExternalFailure failure = RcdExternalFailure.transportFailure();

        assertEquals(RcdExternalFailure.Kind.TRANSPORT_FAILURE, failure.getKind());
        assertTrue(failure.getMessage().contains("may have started"));
        assertTrue(failure.getMessage().contains("verify remote state"));
    }

    @Test
    public void requestAndMalformedResponseFailuresHaveFixedMessages() {
        RcdExternalFailure tooLarge = RcdExternalFailure.requestTooLarge();
        assertEquals(RcdExternalFailure.Kind.REQUEST_TOO_LARGE, tooLarge.getKind());
        assertEquals("The rclone request exceeds the supported size limit.", tooLarge.getMessage());

        RcdExternalFailure malformed = RcdExternalFailure.invalidResponse();
        assertEquals(RcdExternalFailure.Kind.INVALID_RESPONSE, malformed.getKind());
        assertTrue(malformed.getMessage().contains("verify remote state"));
        assertTrue(!malformed.getMessage().contains("secret"));
    }
}
