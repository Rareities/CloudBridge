package ca.pkay.rcloneexplorer.Items;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SyncDirectionObjectTest {

    @Test
    public void unsupportedSavedDirectionsNeverMapToOneWaySync() {
        for (int direction : new int[]{
                SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL,
                SyncDirectionObject.SYNC_BIDIRECTIONAL,
                99
        }) {
            assertEquals(-1, SyncDirectionObject.spinnerPositionForDirection(direction));
            assertNull(SyncDirectionObject.directionForSpinnerPosition(-1));
            assertEquals(Integer.valueOf(direction), SyncDirectionObject.directionForSaving(0, true, direction));
        }
        assertEquals(Integer.valueOf(0), SyncDirectionObject.directionForSaving(0, true, 0));
        assertEquals(Integer.valueOf(-1), SyncDirectionObject.directionForSaving(0, true, -1));
        assertNull(SyncDirectionObject.directionForSaving(-1, false, null));
    }

    @Test
    public void placeholderPreservesUnsupportedValueAndOffsetsExplicitChoices() {
        for (int direction : new int[]{
                SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL,
                SyncDirectionObject.SYNC_BIDIRECTIONAL,
                99
        }) {
            assertEquals(0, SyncDirectionObject.spinnerPositionForDirection(direction, true));
            assertNull(SyncDirectionObject.directionForSpinnerPosition(0, true));
            assertEquals(
                    Integer.valueOf(SyncDirectionObject.SYNC_LOCAL_TO_REMOTE),
                    SyncDirectionObject.directionForSaving(1, true, direction)
            );
        }

        for (int i = 0; i < SyncDirectionObject.SPINNER_TO_DIRECTION.length; i++) {
            int direction = SyncDirectionObject.SPINNER_TO_DIRECTION[i];
            assertEquals(i + 1, SyncDirectionObject.spinnerPositionForDirection(direction, true));
            assertEquals(Integer.valueOf(direction), SyncDirectionObject.directionForSpinnerPosition(i + 1, true));
        }
    }

    @Test
    public void supportedSpinnerPositionsRemainStableWithoutPlaceholder() {
        for (int i = 0; i < SyncDirectionObject.SPINNER_TO_DIRECTION.length; i++) {
            int direction = SyncDirectionObject.SPINNER_TO_DIRECTION[i];
            assertEquals(i, SyncDirectionObject.spinnerPositionForDirection(direction));
            assertEquals(Integer.valueOf(direction), SyncDirectionObject.directionForSpinnerPosition(i));
        }
    }
}
