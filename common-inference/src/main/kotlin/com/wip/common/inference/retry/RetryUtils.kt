package com.wip.common.inference.retry

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.wip.plugintoolkit.api.PluginLogger

private val RATE_LIMIT_REGEX = Regex(
    """(?:\b429\b|RESOURCE_EXHAUSTED|quota exceeded|exceeded your current quota|too many requests|rate limit)""",
    RegexOption.IGNORE_CASE
)

private val RETRY_DELAY_REGEX = Regex(
    """(?:retryDelay["']?\s*:\s*["']?(\d+(?:\.\d+)?)s?["']?|(?:please\s+)?retry\s+in\s+(\d+(?:\.\d+)?)s|retry-after\s*:\s*(\d+))""",
    RegexOption.IGNORE_CASE
)

/**
 * Checks if the given throwable or any cause in its chain represents an HTTP 429 Rate Limit
 * or resource/quota exhaustion error.
 */
fun isRateLimitException(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
        val code = extractStatusCode(current)
        if (code == 429) {
            return true
        }

        val msg = current.message
        if (msg != null && isRateLimitMessage(msg)) {
            return true
        }

        current = current.cause
    }
    return false
}

/**
 * Checks if the given throwable represents a model output failure (such as malformed JSON,
 * JSON decoding exception, serialization error, or output schema/chunk validation mismatch)
 * rather than an API, network, or server failure.
 */
fun isModelOutputException(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
        if (current is kotlinx.serialization.SerializationException) {
            return true
        }
        val className = current::class.simpleName ?: ""
        if (className.contains("Json", ignoreCase = true) ||
            className.contains("Serialization", ignoreCase = true) ||
            className.contains("Decode", ignoreCase = true) ||
            className.contains("Parse", ignoreCase = true)
        ) {
            return true
        }
        val msg = current.message ?: ""
        if (msg.contains("Expected EOF", ignoreCase = true) ||
            msg.contains("Unexpected JSON token", ignoreCase = true) ||
            msg.contains("JSON input", ignoreCase = true) ||
            msg.contains("Expected start of the object", ignoreCase = true) ||
            msg.contains("Expected start of the array", ignoreCase = true) ||
            msg.contains("Mismatch in chunk", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Checks if an error message contains keywords or status codes indicating rate limit exhaustion.
 */
fun isRateLimitMessage(message: String): Boolean {
    return RATE_LIMIT_REGEX.containsMatchIn(message)
}

/**
 * Attempts to extract an HTTP status code from an exception object if present (e.g. from KoogHttpClientException).
 */
fun extractStatusCode(throwable: Throwable): Int? {
    try {
        val prop = throwable::class.members.firstOrNull { it.name == "statusCode" }
        val value = prop?.call(throwable) as? Int
        if (value != null) return value
    } catch (_: Throwable) {
        // ignore reflection errors
    }

    try {
        val statusProp = throwable::class.members.firstOrNull { it.name == "status" }
        val statusObj = statusProp?.call(throwable)
        if (statusObj is Int) return statusObj
        if (statusObj != null) {
            val valProp = statusObj::class.members.firstOrNull { it.name == "value" }
            val valInt = valProp?.call(statusObj) as? Int
            if (valInt != null) return valInt
        }
    } catch (_: Throwable) {
        // ignore reflection errors
    }

    return null
}

/**
 * Attempts to parse a retry delay in milliseconds from the exception message or error body.
 */
fun extractRetryDelayMs(throwable: Throwable): Long? {
    var current: Throwable? = throwable
    while (current != null) {
        val msg = current.message
        if (msg != null) {
            val match = RETRY_DELAY_REGEX.find(msg)
            if (match != null) {
                val secStr = match.groups[1]?.value
                    ?: match.groups[2]?.value
                    ?: match.groups[3]?.value
                if (secStr != null) {
                    val sec = secStr.toDoubleOrNull()
                    if (sec != null && sec > 0) {
                        return (sec * 1000.0).toLong()
                    }
                }
            }
        }
        current = current.cause
    }
    return null
}

/**
 * Cooperatively delays execution in intervals, allowing rapid cancellation detection via [isCancelled].
 */
suspend fun delayWithCancellation(
    delayMs: Long,
    isCancelled: (() -> Boolean)? = null,
    pollIntervalMs: Long = 500L
) {
    if (isCancelled?.invoke() == true) {
        throw CancellationException("Operation cancelled by user")
    }
    var remaining = delayMs
    while (remaining > 0) {
        val step = min(remaining, pollIntervalMs)
        delay(step)
        if (isCancelled?.invoke() == true) {
            throw CancellationException("Operation cancelled by user")
        }
        remaining -= step
    }
}

/**
 * Executes [block] with automatic retry and backoff logic.
 *
 * When an HTTP 429 Rate Limit or Quota Exhaustion is detected, it waits for at least
 * [rateLimitDelayMs] (default 1 minute) or the server's requested delay, plus a slight buffer/jitter,
 * ensuring quota rolling windows (like free-tier token/request buckets) have elapsed.
 *
 * When an error is caused by model output formatting or parsing ([isModelOutputException] or [isModelOutputError]),
 * it uses [modelErrorDelayMs] (default 0ms), retrying immediately without API backoff delay.
 *
 * Standard transient network/API errors use the intervals in [delaysMs].
 */
suspend fun <T> retryWithBackoff(
    logger: PluginLogger? = null,
    delaysMs: List<Long> = listOf(5000L, 10000L, 10000L, 10000L, 15000L),
    rateLimitDelayMs: Long = 60_000L,
    modelErrorDelayMs: Long = 0L,
    maxAttempts: Int = delaysMs.size + 1,
    isCancelled: (() -> Boolean)? = null,
    addJitter: Boolean = true,
    isModelOutputError: ((Throwable) -> Boolean)? = null,
    block: suspend () -> T
): T {
    var lastException: Throwable? = null

    for (attempt in 1..maxAttempts) {
        if (isCancelled?.invoke() == true) {
            throw CancellationException("Operation cancelled by user")
        }

        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastException = e
            if (isCancelled?.invoke() == true) {
                throw CancellationException("Operation cancelled by user", e)
            }

            if (attempt >= maxAttempts) {
                logger?.error("Attempt $attempt failed: ${e::class.simpleName}: ${e.message}. Max retries ($maxAttempts) reached.")
                break
            }

            val isRateLimit = isRateLimitException(e)
            val isModelErr = !isRateLimit && ((isModelOutputError?.invoke(e) == true) || isModelOutputException(e))

            val waitTimeMs = if (isRateLimit) {
                val parsedDelay = extractRetryDelayMs(e) ?: 0L
                val baseDelay = maxOf(rateLimitDelayMs, parsedDelay)
                val jitter = if (addJitter) 1000L + Random.nextLong(0, 1500) else 0L
                baseDelay + jitter
            } else if (isModelErr) {
                modelErrorDelayMs
            } else {
                val delayIndex = min(attempt - 1, delaysMs.size - 1)
                delaysMs[delayIndex]
            }

            if (isRateLimit) {
                val delaySeconds = waitTimeMs / 1000
                logger?.warn(
                    "Attempt $attempt failed due to rate limit (HTTP 429 / Quota exhausted). " +
                    "Waiting ${delaySeconds}s (at least 1 minute) before retrying (attempt ${attempt + 1} of $maxAttempts)..."
                )
            } else if (isModelErr) {
                if (waitTimeMs > 0L) {
                    logger?.warn(
                        "Attempt $attempt failed due to model output formatting/parsing error: ${e::class.simpleName}: ${e.message}. " +
                        "Retrying in ${waitTimeMs}ms (attempt ${attempt + 1} of $maxAttempts)..."
                    )
                } else {
                    logger?.warn(
                        "Attempt $attempt failed due to model output formatting/parsing error: ${e::class.simpleName}: ${e.message}. " +
                        "Retrying immediately without API backoff (attempt ${attempt + 1} of $maxAttempts)..."
                    )
                }
            } else {
                logger?.warn(
                    "Attempt $attempt failed: ${e::class.simpleName}: ${e.message}. " +
                    "Retrying in ${waitTimeMs}ms (attempt ${attempt + 1} of $maxAttempts)..."
                )
            }

            if (waitTimeMs > 0L) {
                delayWithCancellation(waitTimeMs, isCancelled)
            } else if (isCancelled?.invoke() == true) {
                throw CancellationException("Operation cancelled by user")
            }
        }
    }

    throw lastException ?: RuntimeException("Failed after $maxAttempts attempts")
}
