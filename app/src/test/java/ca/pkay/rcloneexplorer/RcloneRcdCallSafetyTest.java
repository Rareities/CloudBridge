package ca.pkay.rcloneexplorer;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RcloneRcdCallSafetyTest {
    @Test
    public void lostTransportResponseRetainsTheResourceClaim() {
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(
                new RcloneRcd.RcdIOException(new IOException("connection reset"))));
    }

    @Test
    public void serverFailureRetainsClaimButDefinitiveClientRejectionDoesNot() {
        RcloneRcd.ErrorResponse serverFailure = new RcloneRcd.ErrorResponse();
        serverFailure.status = 503;
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(new RcloneRcd.RcdOpException(serverFailure)));

        RcloneRcd.ErrorResponse rejected = new RcloneRcd.ErrorResponse();
        rejected.status = 409;
        assertFalse(RcdClaimFailurePolicy.mayHaveStarted(new RcloneRcd.RcdOpException(rejected)));
    }

    @Test
    public void preRequestSerializationFailureAndUnclassifiedRuntimeAreConservative() {
        RcloneRcd.ErrorResponse serializationFailure = new RcloneRcd.ErrorResponse();
        assertFalse(RcdClaimFailurePolicy.mayHaveStarted(
                new RcloneRcd.RcdOpException(serializationFailure)));
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(new IllegalStateException("unknown")));
    }
}
