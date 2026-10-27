package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.http.HttpTimeoutException

/** A failure that a moment's wait fixes: the network or the server, never the credential. */
internal class TransientBrokerFailure(message: String) : IllegalStateException(message)

/** A 5xx: the server, not the request, failed. An [IOException] so [retryTransient] retries it. */
internal class ServerStatusFailure(status: Int) : IOException("HTTP $status")

/** Waits between attempts of [retryTransient]; three attempts in about 1.25 s. */
internal val TRANSIENT_RETRY_DELAYS_MS = listOf(250L, 1_000L)

/**
 * A dropped connection or a 5xx, but not a timeout: a timed-out request already spent its
 * whole wait, and three of them would hold the caller for a minute.
 */
internal fun isTransient(error: Throwable): Boolean =
    error is IOException && error !is HttpTimeoutException

/**
 * Runs [block] again after each wait in [delaysMs] while it fails transiently.
 *
 * The JDK client never retries a POST, and a pooled connection the proxy already closed
 * fails on first use: on 2026-10-27 that failed BOSS AI sign-in on every Optimist pod at
 * once, every few minutes, while fresh connections from the same pods all succeeded.
 */
internal suspend fun <T> retryTransient(delaysMs: List<Long> = TRANSIENT_RETRY_DELAYS_MS, block: suspend () -> T): T {
    for (wait in delaysMs) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isTransient(error)) throw error
        }
        delay(wait)
    }
    return block()
}
