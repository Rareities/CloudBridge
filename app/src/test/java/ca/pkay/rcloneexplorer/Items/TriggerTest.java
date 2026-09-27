package ca.pkay.rcloneexplorer.Items;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TriggerTest {
    @Test
    public void duplicatePreservesConfigurationButUsesUnpersistedIdentity() {
        Trigger original = new Trigger(42L);
        original.setTitle("nightly");
        original.setEnabled(false);
        original.setWeekdays((byte) 0b01010101);
        original.setTime(23 * 60);
        original.setTriggerTarget(19L);
        original.setType(Trigger.TRIGGER_TYPE_INTERVAL);

        Trigger duplicate = original.duplicate("nightly copy");

        assertEquals(Trigger.TRIGGER_ID_DOESNTEXIST, duplicate.getId());
        assertEquals("nightly copy", duplicate.getTitle());
        assertFalse(duplicate.isEnabled());
        assertEquals(original.getWeekdays(), duplicate.getWeekdays());
        assertEquals(original.getTime(), duplicate.getTime());
        assertEquals(original.getTriggerTarget(), duplicate.getTriggerTarget());
        assertEquals(original.getType(), duplicate.getType());
        assertEquals("nightly", original.getTitle());
        assertTrue(duplicate != original);
    }
}
