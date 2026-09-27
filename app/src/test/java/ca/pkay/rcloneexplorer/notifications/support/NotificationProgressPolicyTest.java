package ca.pkay.rcloneexplorer.notifications.support;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NotificationProgressPolicyTest {

    @Test
    public void unknownTotalsRemainAtZero() {
        assertEquals(0, NotificationProgressPolicy.percent(0, 0));
        assertEquals(0, NotificationProgressPolicy.percent(10, 0));
        assertEquals(0, NotificationProgressPolicy.percent(10, -1));
    }

    @Test
    public void negativeBytesRemainAtZero() {
        assertEquals(0, NotificationProgressPolicy.percent(-1, 100));
    }

    @Test
    public void progressIsFlooredAndBounded() {
        assertEquals(33, NotificationProgressPolicy.percent(1, 3));
        assertEquals(100, NotificationProgressPolicy.percent(101, 100));
    }

    @Test
    public void largeValuesDoNotOverflowTheCalculation() {
        assertEquals(50, NotificationProgressPolicy.percent(Long.MAX_VALUE / 2, Long.MAX_VALUE));
    }
}
