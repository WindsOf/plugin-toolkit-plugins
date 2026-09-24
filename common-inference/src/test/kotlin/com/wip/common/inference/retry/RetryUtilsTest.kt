package com.wip.common.inference.retry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DummyHttpException(
    val statusCode: Int,
    message: String
) : Exception(message)

class RetryUtilsTest {

    private val sampleGoogle429Message = """
        Error from client: GoogleLLMClient
        Status code: 429
        Error body:
        {
          "error": {
            "code": 429,
            "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_input_token_count, limit: 16000, model: gemma-4-31b\nPlease retry in 29.943570175s.",
            "status": "RESOURCE_EXHAUSTED",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.Help",
                "links": [
                  {
                    "description": "Learn more about Gemini API quotas",
                    "url": "https://ai.google.dev/gemini-api/docs/rate-limits"
                  }
                ]
              },
              {
                "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                "violations": [
                  {
                    "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_input_token_count",
                    "quotaId": "GenerateContentInputTokensPerModelPerMinute-FreeTier",
                    "quotaDimensions": {
                      "location": "global",
                      "model": "gemma-4-31b"
                    },
                    "quotaValue": "16000"
                  }
                ]
              },
              {
                "@type": "type.googleapis.com/google.rpc.RetryInfo",
                "retryDelay": "29s"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun testIsRateLimitExceptionFromGoogleErrorMessage() {
        val ex = RuntimeException(sampleGoogle429Message)
        assertTrue(isRateLimitException(ex))
    }

    @Test
    fun testIsRateLimitExceptionFromCauseChain() {
        val root = RuntimeException("Status code: 429: Too Many Requests")
        val wrapped = RuntimeException("Basic OCR Translation failed", root)
        assertTrue(isRateLimitException(wrapped))
    }

    @Test
    fun testIsRateLimitExceptionFromStatusCodeProperty() {
        val ex = DummyHttpException(429, "Rate limit reached")
        assertEquals(429, extractStatusCode(ex))
        assertTrue(isRateLimitException(ex))
    }

    @Test
    fun testNonRateLimitException() {
        val ex = IllegalArgumentException("Invalid parameter provided")
        assertFalse(isRateLimitException(ex))
        assertNull(extractRetryDelayMs(ex))
    }

    @Test
    fun testExtractRetryDelayMsFromGoogleResponse() {
        val ex = RuntimeException(sampleGoogle429Message)
        val delayMs = extractRetryDelayMs(ex)
        assertNotNull(delayMs)
        // matches Please retry in 29.943570175s or retryDelay: "29s"
        assertTrue(delayMs in 29000L..30000L, "Expected delay around 29-30s, got $delayMs")
    }

    @Test
    fun testExtractRetryDelayMsFromSentence() {
        val ex = RuntimeException("Quota exceeded. Please retry in 15.5s.")
        val delayMs = extractRetryDelayMs(ex)
        assertNotNull(delayMs)
        assertEquals(15500L, delayMs)
    }

    @Test
    fun testExtractRetryDelayMsFromRetryAfterHeader() {
        val ex = RuntimeException("HTTP 429. Retry-After: 45")
        val delayMs = extractRetryDelayMs(ex)
        assertNotNull(delayMs)
        assertEquals(45000L, delayMs)
    }

    @Test
    fun testRetryWithBackoffSuccessFirstAttempt() {
        runBlocking {
            var callCount = 0
            val result = retryWithBackoff(delaysMs = listOf(10L, 20L)) {
                callCount++
                "success"
            }
            assertEquals("success", result)
            assertEquals(1, callCount)
        }
    }

    @Test
    fun testRetryWithBackoffSucceedsAfterRateLimit() {
        runBlocking {
            var callCount = 0
            val result = retryWithBackoff(
                delaysMs = listOf(10L),
                rateLimitDelayMs = 20L,
                addJitter = false
            ) {
                callCount++
                if (callCount == 1) {
                    throw RuntimeException("Status code: 429 RESOURCE_EXHAUSTED. Please retry in 0.01s.")
                }
                "recovered"
            }
            assertEquals("recovered", result)
            assertEquals(2, callCount)
        }
    }

    @Test
    fun testRetryWithBackoffExhaustionThrows() {
        runBlocking {
            var callCount = 0
            assertFailsWith<RuntimeException> {
                retryWithBackoff(
                    delaysMs = listOf(5L, 5L),
                    maxAttempts = 3,
                    rateLimitDelayMs = 5L,
                    addJitter = false
                ) {
                    callCount++
                    throw RuntimeException("Status code: 429 RESOURCE_EXHAUSTED")
                }
            }
            assertEquals(3, callCount)
        }
    }

    @Test
    fun testRetryWithBackoffRethrowsCancellationException() {
        runBlocking {
            assertFailsWith<CancellationException> {
                retryWithBackoff(delaysMs = listOf(10L)) {
                    throw CancellationException("Cancelled by coroutine scope")
                }
            }
        }
    }

    @Test
    fun testRetryWithBackoffAbortsWhenCancelledFlag() {
        runBlocking {
            var isCancelled = false
            var callCount = 0

            assertFailsWith<CancellationException> {
                retryWithBackoff(
                    delaysMs = listOf(100L),
                    isCancelled = { isCancelled }
                ) {
                    callCount++
                    isCancelled = true
                    throw RuntimeException("Temporary network glitch")
                }
            }
            assertEquals(1, callCount)
        }
    }

    @Test
    fun testIsModelOutputExceptionDetection() {
        val serializationEx = kotlinx.serialization.SerializationException("Failed to decode JSON: Unexpected token")
        assertTrue(isModelOutputException(serializationEx))

        val decodingEx = RuntimeException("JsonDecodingException: Unexpected JSON token at offset 980: Expected EOF after parsing, but had C instead at path: $")
        assertTrue(isModelOutputException(decodingEx))

        val preambleEx = RuntimeException("JsonDecodingException: Expected start of the object '{', but had 'W' instead at path: $")
        assertTrue(isModelOutputException(preambleEx))

        val chunkMismatchEx = IllegalStateException("Mismatch in chunk 1: Expected 50 translations, got 48")
        assertTrue(isModelOutputException(chunkMismatchEx))

        val networkEx = RuntimeException("SocketTimeoutException: Connect timed out")
        assertFalse(isModelOutputException(networkEx))

        val rateLimitEx = RuntimeException("Status code: 429 RESOURCE_EXHAUSTED")
        assertFalse(isModelOutputException(rateLimitEx))
    }

    @Test
    fun testRetryWithBackoffModelErrorImmediateRetry() {
        runBlocking {
            var callCount = 0
            val startTime = System.currentTimeMillis()

            // delaysMs is huge (5000L), but model error delay should be 0L (immediate)
            val result = retryWithBackoff(
                delaysMs = listOf(5000L, 5000L),
                modelErrorDelayMs = 0L
            ) {
                callCount++
                if (callCount < 3) {
                    throw kotlinx.serialization.SerializationException("Malformed JSON formatting from model")
                }
                "recovered_immediately"
            }

            val elapsed = System.currentTimeMillis() - startTime
            assertEquals("recovered_immediately", result)
            assertEquals(3, callCount)
            // It should have executed almost instantaneously (< 1000ms), NOT waiting 10000ms (5000 * 2)
            assertTrue(elapsed < 2000L, "Expected execution without backoff delays, took ${elapsed}ms")
        }
    }
}

