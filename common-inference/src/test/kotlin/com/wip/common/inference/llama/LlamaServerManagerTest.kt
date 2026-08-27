package com.wip.common.inference.llama

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
