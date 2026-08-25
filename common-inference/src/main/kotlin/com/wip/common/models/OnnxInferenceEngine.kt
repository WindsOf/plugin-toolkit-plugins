package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import org.wip.plugintoolkit.api.PluginLogger

/**
 * Supported execution devices for ONNX Runtime.
 */
enum class ExecutionDevice {
    AUTO,
    CUDA,
    DIRECT_ML,
    CORE_ML,
    CPU
}

/**
 * Wrapper holding an active [OrtSession] and metadata about the device used.
 */
class OnnxInferenceSession(
    val session: OrtSession,
    val environment: OrtEnvironment,
    val device: ExecutionDevice
) : AutoCloseable {

    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result {
        return session.run(inputs)
    }

    override fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
    }
}

/**
 * Hardware-accelerated ONNX Runtime engine with automatic GPU provider discovery and CPU fallback.
 */
object OnnxInferenceEngine {
    val environment: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment("WIP_Plugin_ONNX_Env")
    }

    /**
     * Creates an ONNX session from raw byte array weights with automatic GPU discovery and CPU fallback.
     */
    fun createSession(
        modelBytes: ByteArray,
        preferredDevice: ExecutionDevice = ExecutionDevice.AUTO,
        logger: PluginLogger? = null
    ): OnnxInferenceSession {
        return createSessionInternal(preferredDevice, logger) { opts ->
            environment.createSession(modelBytes, opts)
        }
    }

    /**
     * Creates an ONNX session from a file path with automatic GPU discovery and CPU fallback.
     */
    fun createSession(
        modelPath: String,
        preferredDevice: ExecutionDevice = ExecutionDevice.AUTO,
        logger: PluginLogger? = null
    ): OnnxInferenceSession {
        return createSessionInternal(preferredDevice, logger) { opts ->
            environment.createSession(modelPath, opts)
        }
    }

    private inline fun createSessionInternal(
        preferredDevice: ExecutionDevice,
        logger: PluginLogger?,
        sessionCreator: (OrtSession.SessionOptions) -> OrtSession
    ): OnnxInferenceSession {
        if (preferredDevice == ExecutionDevice.CPU) {
            logger?.info("Creating ONNX session on CPU (explicitly requested)")
            val cpuOptions = OrtSession.SessionOptions()
            val session = sessionCreator(cpuOptions)
            return OnnxInferenceSession(session, environment, ExecutionDevice.CPU)
        }

        // Try GPU providers if AUTO or specific GPU device requested
        val providersToTry = when (preferredDevice) {
            ExecutionDevice.CUDA -> listOf(ExecutionDevice.CUDA)
            ExecutionDevice.DIRECT_ML -> listOf(ExecutionDevice.DIRECT_ML)
            ExecutionDevice.CORE_ML -> listOf(ExecutionDevice.CORE_ML)
            ExecutionDevice.AUTO -> listOf(
                ExecutionDevice.CUDA,
                ExecutionDevice.DIRECT_ML,
                ExecutionDevice.CORE_ML
            )
            ExecutionDevice.CPU -> emptyList()
        }

        for (device in providersToTry) {
            try {
                val gpuOptions = OrtSession.SessionOptions()
                var configured = false

                when (device) {
                    ExecutionDevice.CUDA -> {
                        try {
                            gpuOptions.addCUDA(0)
                            configured = true
                        } catch (t: Throwable) {
                            logger?.debug("CUDA provider registration failed: ${t.message}")
                        }
                    }
                    ExecutionDevice.DIRECT_ML -> {
                        try {
                            gpuOptions.addDirectML(0)
                            configured = true
                        } catch (t: Throwable) {
                            logger?.debug("DirectML provider registration failed: ${t.message}")
                        }
                    }
                    ExecutionDevice.CORE_ML -> {
                        try {
                            gpuOptions.addCoreML()
                            configured = true
                        } catch (t: Throwable) {
                            logger?.debug("CoreML provider registration failed: ${t.message}")
                        }
                    }
                    else -> {}
                }

                if (configured) {
                    val session = sessionCreator(gpuOptions)
                    logger?.info("Successfully created ONNX session using GPU acceleration ($device)")
                    return OnnxInferenceSession(session, environment, device)
                }
            } catch (t: Throwable) {
                val errorMsg = t.message ?: t.toString()
                if (device == ExecutionDevice.CUDA && errorMsg.contains("error 126")) {
                    logger?.warn("CUDA GPU acceleration unavailable: Missing CUDA 12 runtime or cuDNN DLLs in PATH (cudart64_12.dll, cublas64_12.dll, cudnn64_9.dll).")
                } else if (!errorMsg.contains("not compiled with")) {
                    logger?.debug("Could not initialize session with $device: $errorMsg")
                }
            }
        }

        logger?.info("Using CPU execution provider for ONNX session")
        val cpuOptions = OrtSession.SessionOptions()
        val session = sessionCreator(cpuOptions)
        return OnnxInferenceSession(session, environment, ExecutionDevice.CPU)
    }
}
