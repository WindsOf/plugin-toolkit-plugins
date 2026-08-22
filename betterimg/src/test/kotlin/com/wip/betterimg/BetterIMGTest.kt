package com.wip.betterimg

import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BetterIMGTest {

    private class FakeLogger : PluginLogger {
        val messages = mutableListOf<String>()
        override fun verbose(message: String) { messages.add("VERBOSE: $message") }
        override fun debug(message: String) { messages.add("DEBUG: $message") }
        override fun info(message: String) { messages.add("INFO: $message") }
        override fun warn(message: String) { messages.add("WARN: $message") }
        override fun error(message: String, throwable: Throwable?) { messages.add("ERROR: $message") }
    }

    @Test
    fun testOutputFormatEnum() {
        assertEquals("PNG", OutputFormat.PNG.name)
        assertEquals("WEBP", OutputFormat.WEBP.name)
    }

    @Test
    fun testWidthsEnum() {
        assertEquals("x1", Widths.x1.name)
        assertEquals("x2", Widths.x2.name)
        assertEquals("x4", Widths.x4.name)
        assertEquals("x8", Widths.x8.name)
    }

    @Test
    fun testLifecycleHooks() {
        val plugin = BetterIMG()
        val logger = FakeLogger()
        val loadResult = plugin.onLoad(logger)
        assertTrue(loadResult.isSuccess)

        val tempBase = File("build/tmp/test_betterimg_base").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val pythonExe = File(tempBase, "vapoursynth-portable/python.exe").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        val coreScript = File(tempBase, "upscaler_core.py").apply {
            createNewFile()
        }

        val fileSystem = io.mockk.mockk<PluginFileSystem> {
            io.mockk.every { getBasePath() } returns tempBase.absolutePath
        }
        val context = io.mockk.mockk<PluginContext>(relaxed = true) {
            io.mockk.every { this@mockk.fileSystem } returns fileSystem
            io.mockk.every { this@mockk.logger } returns logger
        }

        kotlinx.coroutines.runBlocking {
            val validateResult = plugin.validate(context)
            assertTrue(validateResult.isSuccess, "Validation should succeed when pythonExe and coreScript exist")
        }
    }
}
