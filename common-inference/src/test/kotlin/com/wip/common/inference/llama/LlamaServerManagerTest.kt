package com.wip.common.inference.llama

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.wip.plugintoolkit.api.PluginFileSystem

class LlamaServerManagerTest {

    @Test
    fun testResolveArchiveName() {
        val cudaArchive = LlamaBinaryDownloader.resolveArchiveName(LlamaBackend.CUDA)
        val vulkanArchive = LlamaBinaryDownloader.resolveArchiveName(LlamaBackend.VULKAN)
        val cpuArchive = LlamaBinaryDownloader.resolveArchiveName(LlamaBackend.CPU)

        assertTrue(cudaArchive.contains("cuda-cu12"), "CUDA archive should contain cuda-cu12")
        assertTrue(vulkanArchive.contains("vulkan"), "Vulkan archive should contain vulkan")
        assertTrue(cpuArchive.contains("cpu"), "CPU archive should contain cpu")
    }

    @Test
    fun testConfigDefaults() {
        val config = LlamaServerConfig()
        assertEquals(LlamaServerMode.AUTO, config.mode)
        assertEquals(LlamaBackend.AUTO, config.backend)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(99, config.gpuLayers)
    }

    @Test
    fun testFindFreePort() {
        val port = LlamaServerManager.findFreePort()
        assertTrue(port > 1024, "Free port should be non-privileged (>1024)")
    }

    @Test
    fun testResolveCustomExecutable() = runBlocking {
        val tempExe = File.createTempFile("test-llama-server", ".exe")
        tempExe.setExecutable(true)
        tempExe.deleteOnExit()

        val mockFs = mockk<PluginFileSystem>()
        every { mockFs.getBasePath() } returns (tempExe.parentFile?.absolutePath ?: ".")

        val manager = LlamaServerManager()
        val config = LlamaServerConfig(
            mode = LlamaServerMode.CUSTOM,
            customPath = tempExe.absolutePath
        )

        val resolved = manager.resolveExecutable(config, mockFs)
        assertEquals(tempExe.absolutePath, resolved)
    }

    @Test
    fun testRemoteSessionResolution() = runBlocking {
        val mockClient = mockk<LlamaInferenceClient>()
        coEvery { mockClient.checkHealth("http://localhost:1234", any()) } returns true

        val mockFs = mockk<PluginFileSystem>()
        val manager = LlamaServerManager(client = mockClient)

        val config = LlamaServerConfig(
            mode = LlamaServerMode.REMOTE,
            remoteUrl = "http://localhost:1234"
        )

        val session = manager.getOrStartServer("test-model.gguf", config, mockFs)
        assertTrue(session.isRemote)
        assertEquals("http://localhost:1234", session.baseUrl)
        assertEquals("test-model.gguf", session.modelPath)
    }

    @Test
    fun testSystemInstallDirectory() {
        val sysDir = LlamaBinaryDownloader.Default.getSystemInstallDirectory()
        assertNotNull(sysDir)
        assertTrue(sysDir.path.isNotEmpty())
    }

    @Test
    fun testDetectInstallationWithCustomPath() {
        val tempExe = File.createTempFile("detect-llama-server", ".exe")
        tempExe.setExecutable(true)
        tempExe.deleteOnExit()

        val manager = LlamaServerManager()
        val detection = manager.detectInstallation(customPath = tempExe.absolutePath)

        assertTrue(detection.found)
        assertEquals(tempExe.absolutePath, detection.executablePath)
        assertEquals("CUSTOM_PATH", detection.source)
    }

    @Test
    fun testDetectInstallationNotFound() {
        val manager = LlamaServerManager()
        val detection = manager.detectInstallation(customPath = "C:/non/existent/path/llama-server.exe")
        assertNotNull(detection)
    }

    @Test
    fun testExecutableCandidates() {
        val candidates = LlamaServerManager.getExecutableCandidates()
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it.contains("llama-server") })
        assertTrue(candidates.any { it.contains("llama") })
    }

    @Test
    fun testFindRunningProcessesDoesNotThrow() {
        val running = LlamaServerManager.findRunningProcesses()
        assertNotNull(running)
        // Should not throw and returns a list
        val hasAny = LlamaServerManager.hasRunningProcesses()
        assertEquals(running.isNotEmpty(), hasAny)
    }

    @Test
    fun testReuseRunningServerOnPort() = runBlocking {
        val mockClient = mockk<LlamaInferenceClient>()
        coEvery { mockClient.checkHealth("http://127.0.0.1:8080", any()) } returns true

        val mockFs = mockk<PluginFileSystem>()
        val manager = LlamaServerManager(client = mockClient)

        val config = LlamaServerConfig(
            port = 8080,
            host = "127.0.0.1"
        )

        val session = manager.getOrStartServer("models/test.gguf", config, mockFs)
        assertNotNull(session)
        assertEquals("http://127.0.0.1:8080", session.baseUrl)
        assertEquals("models/test.gguf", session.modelPath)

        // Stopping active server
        manager.stopActiveServer()
    }
}
