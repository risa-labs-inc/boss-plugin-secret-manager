package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import javax.net.ssl.SSLException
import kotlin.random.Random

/** A failure that a moment's wait fixes: the network or the server, never the credential. */
internal class TransientBrokerFailure(message: String) : IllegalStateException(message)

/** A 5xx: the server, not the request, failed. An [IOException] so [retryTransient] retries it. */
internal class ServerStatusFailure(val status: Int) : IOException("HTTP $status")

/** Waits between attempts of [retryTransient]; three attempts in about 1.25 s, plus jitter. */
internal val TRANSIENT_RETRY_DELAYS_MS = listOf(250L, 1_000L)

/**
 * A dropped connection or a 5xx. Not a timeout: a timed-out request already spent its whole
 * wait, and three of them would hold the caller for a minute. Not a refused connection or an
 * unknown host either: a stopped local server or an offline machine stays that way, and every
 * provider's catalog fetch would pay the retries for nothing. Nor a TLS failure: an
 * intercepting proxy or a bad certificate does not fix itself.
 */
internal fun isTransient(error: Throwable): Boolean =
    error is IOException && error !is HttpTimeoutException &&
        error !is ConnectException && error !is UnknownHostException && error !is SSLException

/** What a failure message may say about [error]: its class, or the status of a 5xx. Never its text. */
internal fun failureLabel(error: Throwable): String =
    if (error is ServerStatusFailure) "HTTP ${error.status}" else error::class.simpleName.orEmpty()

/**
 * Runs [block] again after each wait in [delaysMs] while it fails transiently.
 *
 * The JDK client never retries a POST, and a pooled connection the proxy already closed
 * fails on first use: on 2026-10-27 that failed BOSS AI sign-in on every Optimist pod at
 * once, every few minutes, while fresh connections from the same pods all succeeded.
 * Up to half of each wait is added at random, so a fleet that failed together does not
 * retry together.
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
        delay(wait + Random.nextLong(wait / 2 + 1))
    }
    return block()
}
