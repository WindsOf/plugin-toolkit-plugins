package com.wip.common.inference.llama

import kotlinx.serialization.Serializable

/**
 * Operating mode for resolving the llama-server executable.
 */
@Serializable
enum class LlamaServerMode {
    /**
     * Automatically detect system installation, or download prebuilt binaries if not found.
     */
    AUTO,

    /**
     * Only use llama-server from the system PATH.
     */
    SYSTEM,

    /**
     * Use a user-specified custom binary path or directory.
     */
    CUSTOM,

    /**
     * Connect to an existing remote or local server URL (no process spawned).
     */
    REMOTE
}

/**
 * Hardware acceleration backend for llama-server.
 */
@Serializable
enum class LlamaBackend {
    /**
     * Automatically select the best backend available (CUDA -> Vulkan -> CPU).
     */
    AUTO,

    /**
     * NVIDIA CUDA GPU acceleration (CUDA 12).
     */
    CUDA,

    /**
     * Vulkan GPU acceleration (cross-vendor AMD/NVIDIA/Intel).
     */
    VULKAN,

    /**
     * Pure CPU execution (AVX2 / standard CPU).
     */
    CPU
}

/**
 * Full configuration for launching or connecting to a llama-server instance.
 */
@Serializable
data class LlamaServerConfig(
    val mode: LlamaServerMode = LlamaServerMode.AUTO,
    val backend: LlamaBackend = LlamaBackend.AUTO,
    val customPath: String? = null,
    val remoteUrl: String? = null,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val gpuLayers: Int = 99,
    val contextSize: Int = 8192,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val startupTimeoutSeconds: Long = 60L,
    val mmprojPath: String? = null,
    val extraArgs: List<String> = emptyList()
)
