package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ForegroundPromotionTest {
    @Test
    fun completedPromotionIsAccepted() {
        assertNull(awaitForegroundPromotion(CompletableFuture.completedFuture(Unit)))
    }

    @Test
    fun failedPromotionReturnsThePlatformFailure() {
        val failure = IllegalStateException("foreground service start denied")
        val promotion = CompletableFuture<Unit>().apply { completeExceptionally(failure) }

        assertSame(failure, awaitForegroundPromotion(promotion))
    }

    @Test
    fun timedOutPromotionIsNotAccepted() {
        val promotion = CompletableFuture<Unit>()
        val failure = awaitForegroundPromotion(
            promotion,
            timeout = 1,
            unit = TimeUnit.MILLISECONDS
        )

        assertTrue(failure is java.util.concurrent.TimeoutException)
        assertTrue(promotion.isCancelled)
    }

    @Test
    fun interruptionIsReportedAndRestored() {
        Thread.currentThread().interrupt()
        try {
            val failure = awaitForegroundPromotion(CompletableFuture<Unit>())

            assertTrue(failure is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
