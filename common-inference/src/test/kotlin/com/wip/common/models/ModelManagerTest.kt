package com.wip.common.models

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginStorage
import org.wip.plugintoolkit.api.RelativePath
import org.wip.plugintoolkit.api.toRelativePath
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

    @Test
    fun testResolveTargetFolderForFile() {
        assertEquals("Qwen3-VL-4B-Instruct-GGUF", ModelManager.resolveTargetFolderForFile("Qwen3-VL-4B-Instruct-Q4_K_M.gguf"))
        assertEquals("Qwen3-VL-4B-Instruct-GGUF", ModelManager.resolveTargetFolderForFile("mmproj-Qwen3-VL-4B-Instruct-F16.gguf"))
        assertEquals("Qwen3-VL-4B-Instruct-GGUF", ModelManager.resolveTargetFolderForFile("Qwen3-VL-4B-Instruct-Q4_K_M.yaml"))
        assertEquals("Qwen3-VL-8B-Instruct-GGUF", ModelManager.resolveTargetFolderForFile("Qwen3-VL-8B-Instruct-Q8_0.gguf"))
        assertEquals("Unlimited-OCR", ModelManager.resolveTargetFolderForFile("Unlimited-OCR-Q4_K_M.gguf"))
        assertEquals("Unlimited-OCR", ModelManager.resolveTargetFolderForFile("mmproj-Unlimited-OCR-F16.gguf"))
        assertEquals("big-lama", ModelManager.resolveTargetFolderForFile("big-lama.onnx"))
        assertEquals("big-lama", ModelManager.resolveTargetFolderForFile("big-lama.yaml"))
        assertEquals("yolo-det-x-best-v3", ModelManager.resolveTargetFolderForFile("yolo-det-x-best-v3.onnx"))
        assertEquals("rfdetr-seg-2xlarge-ema-v3", ModelManager.resolveTargetFolderForFile("rfdetr-seg-2xlarge-ema-v3.onnx"))
        assertEquals("Places_512_FullData_G", ModelManager.resolveTargetFolderForFile("Places_512_FullData_G.onnx"))
        assertEquals("zits", ModelManager.resolveTargetFolderForFile("zits.yaml"))
        assertEquals("zitspp", ModelManager.resolveTargetFolderForFile("tsr.onnx"))
    }

    @Test
    fun testOrganizeModelsDirectoryMovesLooseFilesToSubfolders() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        val qwenGgufRel = "models/Qwen3-VL-4B-Instruct-Q4_K_M.gguf".toRelativePath().getOrThrow()
        val qwenMmprojRel = "models/mmproj-Qwen3-VL-4B-Instruct-F16.gguf".toRelativePath().getOrThrow()
        val lamaRel = "models/big-lama.onnx".toRelativePath().getOrThrow()

        fs.writeFile(qwenGgufRel, byteArrayOf(1, 2, 3))
        fs.writeFile(qwenMmprojRel, byteArrayOf(4, 5, 6))
        fs.writeFile(lamaRel, byteArrayOf(7, 8, 9))

        val organizeResult = manager.organizeModelsDirectory(fs)
        assertTrue(organizeResult.isSuccess)
        assertEquals(3, organizeResult.getOrNull())

        // Source loose files should no longer exist
        assertFalse(fs.exists(qwenGgufRel))
        assertFalse(fs.exists(qwenMmprojRel))
        assertFalse(fs.exists(lamaRel))

        // Target organized files should exist
        val targetQwenGguf = "models/Qwen3-VL-4B-Instruct-GGUF/Qwen3-VL-4B-Instruct-Q4_K_M.gguf".toRelativePath().getOrThrow()
        val targetQwenMmproj = "models/Qwen3-VL-4B-Instruct-GGUF/mmproj-Qwen3-VL-4B-Instruct-F16.gguf".toRelativePath().getOrThrow()
        val targetLama = "models/big-lama/big-lama.onnx".toRelativePath().getOrThrow()

        assertTrue(fs.exists(targetQwenGguf))
        assertTrue(fs.exists(targetQwenMmproj))
        assertTrue(fs.exists(targetLama))
    }

    @Test
    fun testOrganizeModelsDirectoryRemovesDuplicateLooseFiles() = runBlocking {
        val fs = FakePluginFileSystem()
        val manager = ModelManager.Default

        val looseLama = "models/big-lama.onnx".toRelativePath().getOrThrow()
        val targetLama = "models/big-lama/big-lama.onnx".toRelativePath().getOrThrow()

        fs.writeFile(looseLama, byteArrayOf(1, 2, 3))
        fs.writeFile(targetLama, byteArrayOf(1, 2, 3))

        val organizeResult = manager.organizeModelsDirectory(fs)
        assertTrue(organizeResult.isSuccess)
        assertEquals(1, organizeResult.getOrNull())

        assertFalse(fs.exists(looseLama))
        assertTrue(fs.exists(targetLama))
    }

    @Test
    fun testFindModelFileRelativePathInSubfolder() = runBlocking {
        val fs = FakePluginFileSystem()

        val subfolderPath = "models/Qwen3-VL-4B-Instruct-GGUF/Qwen3-VL-4B-Instruct-Q4_K_M.gguf".toRelativePath().getOrThrow()
        fs.writeFile(subfolderPath, byteArrayOf(10, 20))

        val found = ModelManager.findModelFileRelativePath(
            ModelCatalog.QWEN3_VL_4B_Q4_K_M_ID,
            "Qwen3-VL-4B-Instruct-Q4_K_M.gguf",
            fs
        )
        assertNotNull(found)
        assertEquals(subfolderPath, found)
    }
}
