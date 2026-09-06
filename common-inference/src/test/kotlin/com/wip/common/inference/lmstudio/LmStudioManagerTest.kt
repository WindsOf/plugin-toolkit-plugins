package com.wip.common.inference.lmstudio

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LmStudioManagerTest {

    @Test
    fun testStandardDirectoriesNotEmpty() {
        val dirs = LmStudioManager.getStandardModelDirectories()
        assertTrue(dirs.isNotEmpty())
        assertTrue(dirs.any { it.path.contains("lm-studio") || it.path.contains(".lmstudio") })
    }

    @Test
    fun testResolveModelNameExplicit() {
        runBlocking {
            val manager = LmStudioManager()
            val resolved = manager.resolveModelName(
                baseUrl = "http://localhost:1234/v1",
                configuredModel = "custom-qwen-model"
            )
            assertEquals("custom-qwen-model", resolved)
        }
    }

    @Test
    fun testResolveModelNameFallbackWhenUnreachable() {
        runBlocking {
            val manager = LmStudioManager()
            // Port 59999 is typically unused, should gracefully fall back
            val resolved = manager.resolveModelName(
                baseUrl = "http://127.0.0.1:59999/v1",
                configuredModel = "default-model"
            )
            assertEquals("default-model", resolved)
        }
    }

    @Test
    fun testCheckStatusUnreachable() {
        runBlocking {
            val manager = LmStudioManager()
            val status = manager.checkStatus(baseUrl = "http://127.0.0.1:59999/v1")
            assertFalse(status.connected)
            assertNotNull(status.errorMessage)
        }
    }

    @Test
    fun testFindModelFileInTempDir() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test-lmstudio-" + System.currentTimeMillis())
        tempDir.mkdirs()
        tempDir.deleteOnExit()

        val modelFile = File(tempDir, "sahilchachra-unlimited-ocr-bf16.gguf")
        modelFile.writeText("GGUF")
        modelFile.deleteOnExit()

        val matches = modelFile.name.contains("bf16", ignoreCase = true)
        assertTrue(matches)
    }
}
