package ca.pkay.rcloneexplorer.workmanager

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

internal fun awaitForegroundPromotion(
    promotion: Future<*>,
    timeout: Long = 10,
    unit: TimeUnit = TimeUnit.SECONDS
): Throwable? = try {
    promotion.get(timeout, unit)
    null
} catch (interrupted: InterruptedException) {
    promotion.cancel(true)
    Thread.currentThread().interrupt()
    interrupted
} catch (failed: ExecutionException) {
    failed.cause ?: failed
} catch (timeoutFailure: TimeoutException) {
    promotion.cancel(true)
    timeoutFailure
} catch (failure: Exception) {
    failure
}

internal fun requireForegroundPromotion(
    promotion: Future<*>,
    timeout: Long = 10,
    unit: TimeUnit = TimeUnit.SECONDS
) {
    val failure = awaitForegroundPromotion(promotion, timeout, unit) ?: return
    throw IllegalStateException("Foreground promotion was not confirmed", failure)
}
