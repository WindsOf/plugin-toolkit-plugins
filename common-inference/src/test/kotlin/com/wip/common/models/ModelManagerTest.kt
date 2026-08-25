package com.wip.common.models

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginStorage
import org.wip.plugintoolkit.api.RelativePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakePluginFileSystem : PluginFileSystem {
    val files = mutableMapOf<String, ByteArray>()
    var rootPath: String = "C:/tmp/test-plugin"

    override suspend fun readFile(relativePath: RelativePath): ByteArray? = files[relativePath.value]
    override suspend fun readTextFile(relativePath: RelativePath): String? = files[relativePath.value]?.decodeToString()
    override suspend fun writeFile(relativePath: RelativePath, data: ByteArray): Result<Unit> {
        files[relativePath.value] = data
        return Result.success(Unit)
    }
    override suspend fun writeTextFile(relativePath: RelativePath, text: String): Result<Unit> {
        files[relativePath.value] = text.encodeToByteArray()
        return Result.success(Unit)
    }
    override suspend fun exists(relativePath: RelativePath): Boolean = files.containsKey(relativePath.value)
    override suspend fun listFiles(relativePath: RelativePath): List<String> = files.keys.filter { it.startsWith(relativePath.value) }
    override suspend fun deleteFile(relativePath: RelativePath): Result<Unit> {
        files.remove(relativePath.value)
        return Result.success(Unit)
    }
    override fun getBasePath(): String = rootPath
    override suspend fun extractResource(resourcePath: String, targetRelativePath: RelativePath): Result<Unit> = Result.success(Unit)
}

class FakePluginStorage : PluginStorage {
    val storage = mutableMapOf<String, JsonElement>()

    override suspend fun get(key: String): JsonElement? = storage[key]
    override suspend fun put(key: String, value: JsonElement) {
        storage[key] = value
    }
    override suspend fun getAll(): Map<String, JsonElement> = storage
    override suspend fun remove(key: String) {
        storage.remove(key)
    }
}

class ModelManagerTest {

    @Test
    fun testLockStateWhenNotInstalled() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        assertFalse(manager.isModelInstalled(ModelCatalog.YOLO_DET_X_ID, fs))
        assertFalse(manager.isModelInstalled(ModelCatalog.RFDETR_SEG_2XLARGE_ID, fs))

        val locks = manager.getLocksState(fs)
        assertEquals(false, locks["model:${ModelCatalog.YOLO_DET_X_ID}"])
        assertEquals(false, locks["model:${ModelCatalog.RFDETR_SEG_2XLARGE_ID}"])
    }

    @Test
    fun testLockStateWhenInstalled() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        val yamlContent = """
            type: yolov10
            name: yolo-det-x-best-v3
            display_name: Yolo-Det-X-Best-V3
            model_path: yolo-det-x-best-v3.onnx
            input_width: 640
            input_height: 640
            classes:
            - balloon
        """.trimIndent()

        fs.writeTextFile(ModelManager.getModelYamlRelativePath(ModelCatalog.YOLO_DET_X_ID), yamlContent)
        fs.writeFile(ModelManager.getModelOnnxRelativePath(ModelCatalog.YOLO_DET_X_ID), byteArrayOf(1, 2, 3, 4))

        assertTrue(manager.isModelInstalled(ModelCatalog.YOLO_DET_X_ID, fs))
        assertFalse(manager.isModelInstalled(ModelCatalog.RFDETR_SEG_2XLARGE_ID, fs))

        val locks = manager.getLocksState(fs)
        assertEquals(true, locks["model:${ModelCatalog.YOLO_DET_X_ID}"])
        assertEquals(true, locks[ModelCatalog.YOLO_DET_X_ID])
        assertEquals(false, locks["model:${ModelCatalog.RFDETR_SEG_2XLARGE_ID}"])

        val spec = manager.getModelSpec(ModelCatalog.YOLO_DET_X_ID, fs)
        assertNotNull(spec)
        assertEquals("yolo-det-x-best-v3", spec.name)

        val bytes = manager.getModelBytes(ModelCatalog.YOLO_DET_X_ID, fs)
        assertNotNull(bytes)
        assertEquals(4, bytes.size)

        val path = manager.getModelAbsolutePath(ModelCatalog.YOLO_DET_X_ID, fs)
        assertTrue(path.endsWith("models/yolo-det-x-best-v3.onnx"))
    }

    @Test
    fun testCreateInferenceSessionReturnsNullForMissingModel() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default
        val session = manager.createInferenceSession("non-existent-model", fs)
        assertNull(session)
    }

    @Test
    fun testMultiFileModelInstallation() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        val yamlContent = """
            name: Unlimited-OCR-BF16
            model_type: ocr
            format: onnx
            onnx_file: Unlimited-OCR-BF16.onnx
            data_file: Unlimited-OCR-BF16.onnx.data
            external_data: true
        """.trimIndent()

        val yamlRelPath = ModelManager.getModelYamlRelativePath(ModelCatalog.UNLIMITED_OCR_BF16_ID)
        val onnxRelPath = ModelManager.getModelFileRelativePath("Unlimited-OCR-BF16.onnx")
        val dataRelPath = ModelManager.getModelFileRelativePath("Unlimited-OCR-BF16.onnx.data")

        fs.writeTextFile(yamlRelPath, yamlContent)
        fs.writeFile(onnxRelPath, byteArrayOf(1, 2, 3))

        // When only .onnx exists but .onnx.data is missing, isModelInstalled should be false
        assertFalse(manager.isModelInstalled(ModelCatalog.UNLIMITED_OCR_BF16_ID, fs))

        // When .onnx.data is also created, isModelInstalled should become true
        fs.writeFile(dataRelPath, byteArrayOf(4, 5, 6))
        assertTrue(manager.isModelInstalled(ModelCatalog.UNLIMITED_OCR_BF16_ID, fs))
    }

    @Test
    fun testGgufModelInstallation() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        val yamlContent = """
            name: Unlimited-OCR-Q4_K_M
            model_type: ocr
            format: gguf
            gguf_file: Unlimited-OCR-Q4_K_M.gguf
        """.trimIndent()

        val yamlRelPath = ModelManager.getModelYamlRelativePath(ModelCatalog.UNLIMITED_OCR_Q4_K_M_ID)
        val ggufRelPath = ModelManager.getModelFileRelativePath("Unlimited-OCR-Q4_K_M.gguf")

        fs.writeTextFile(yamlRelPath, yamlContent)
        fs.writeFile(ggufRelPath, byteArrayOf(10, 20, 30))
        assertTrue(manager.isModelInstalled(ModelCatalog.UNLIMITED_OCR_Q4_K_M_ID, fs))
    }

    @Test
    fun testDownloadModelSkipsIfAlreadyInstalled() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        val yamlContent = """
            type: yolov10
            name: yolo-det-x-best-v3
            display_name: Yolo-Det-X-Best-V3
            model_path: yolo-det-x-best-v3.onnx
            input_width: 640
            input_height: 640
            classes:
            - balloon
        """.trimIndent()

        fs.writeTextFile(ModelManager.getModelYamlRelativePath(ModelCatalog.YOLO_DET_X_ID), yamlContent)
        fs.writeFile(ModelManager.getModelOnnxRelativePath(ModelCatalog.YOLO_DET_X_ID), byteArrayOf(1, 2, 3, 4))

        val context = io.mockk.mockk<org.wip.plugintoolkit.api.PluginContext>(relaxed = true)
        io.mockk.every { context.fileSystem } returns fs

        val result = manager.downloadModel(ModelCatalog.YOLO_DET_X_ID, context)
        assertTrue(result.isSuccess)
        assertEquals("yolo-det-x-best-v3", result.getOrNull()?.name)
    }
}
