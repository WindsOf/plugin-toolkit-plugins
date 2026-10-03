package com.wip.manhwaTranslatorAI

import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import com.wip.common.inference.llm.ReasoningEffortLevel
import io.ktor.client.HttpClient
import java.io.File
import java.util.zip.ZipFile
import org.junit.Test
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.decodeFromJsonElement

class TranslatorAITest {

    private class FakeLogger : PluginLogger {
        val messages = mutableListOf<String>()
        override fun verbose(message: String) {
            messages.add("VERBOSE: $message")
        }

        override fun debug(message: String) {
            messages.add("DEBUG: $message")
        }

        override fun info(message: String) {
            messages.add("INFO: $message")
        }

        override fun warn(message: String) {
            messages.add("WARN: $message")
        }

        override fun error(message: String, throwable: Throwable?) {
            messages.add("ERROR: $message")
        }
    }

    @Test
    fun testTranslatorAISettingsDefaults() {
        val settings = TranslatorAISettings(googleApiKey = "test-key-456")
        assertEquals("test-key-456", settings.googleApiKey)
        assertEquals(true, settings.useStructuredOutput)
        assertEquals("http://localhost:1234/v1", settings.lmStudioUrl)
        assertEquals("lm-studio", settings.lmStudioApiKey)
    }

    @Test
    fun testAIModelIdentifiers() {
        assertEquals("gemma-4-26b-a4b-it", AIModel.GEMMA_26B.id)
        assertEquals("gemma-4-31b-it", AIModel.GEMMA_31B.id)
        assertEquals("gemini-3.5-flash", AIModel.GEMINI_3_5_FLASH.id)
        assertEquals("gemini-3.8-flash", AIModel.GEMINI_3_8_FLASH.id)
        assertEquals("gemini-3.1-flash-lite", AIModel.GEMINI_3_1_FLASH_LITE.id)
        assertEquals("lm-studio", AIModel.LM_STUDIO.id)
    }

    @Test
    fun testLifecycleHooks() {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = "key123"))
        val logger = FakeLogger()

        val loadResult = plugin.onLoad(logger)
        assertTrue(loadResult.isSuccess)

        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        kotlinx.coroutines.runBlocking {
            val setupResult = plugin.setup(context)
            assertTrue(setupResult.isSuccess)

            val updateResult = plugin.update(context)
            assertTrue(updateResult.isSuccess)

            val validateResult = plugin.validate(context)
            assertTrue(validateResult.isSuccess)
        }
    }

    @Test
    fun testOCRResultCopyPreservation() {
        val initialOcr = OCRResult(
            texts = listOf("Korean text 1", "Korean text 2"),
            bb = listOf(listOf(0.1, 0.1, 0.3, 0.3), listOf(0.4, 0.4, 0.6, 0.6)),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("01.png", "01.png"),
            failedFiles = emptyList()
        )

        val translatedTexts = listOf("Testo italiano 1", "Testo italiano 2")
        val translatedOcr = initialOcr.copy(texts = translatedTexts)

        assertEquals(translatedTexts, translatedOcr.texts)
        assertEquals(initialOcr.bb, translatedOcr.bb)
        assertEquals(initialOcr.pageNames, translatedOcr.pageNames)
    }

    @Test
    fun testAdvancedOCRResultCopyPreservation() {
        val initialOcr = AdvancedOCRResult(
            texts = listOf("Korean text"),
            balloonBoxes = listOf(listOf(0.1, 0.1, 0.5, 0.5)),
            textBoxes = listOf(listOf(0.15, 0.15, 0.45, 0.45)),
            shapes = listOf("oval"),
            fontStyles = listOf("bold"),
            fontFamilies = listOf("sans-serif"),
            textAngles = listOf(0.0),
            isSparse = listOf(false),
            textColors = listOf("#000000"),
            hasBorder = listOf(true),
            borderColors = listOf("#FFFFFF"),
            pageNumbers = listOf(1),
            pageNames = listOf("01.png"),
            failedFiles = emptyList()
        )

        val translatedTexts = listOf("Testo tradotto")
        val translatedOcr = initialOcr.copy(texts = translatedTexts)

        assertEquals(translatedTexts, translatedOcr.texts)
        assertEquals(initialOcr.balloonBoxes, translatedOcr.balloonBoxes)
        assertEquals(initialOcr.shapes, translatedOcr.shapes)
        assertEquals(initialOcr.borderColors, translatedOcr.borderColors)
    }

    @Test
    fun testIsHallucinationFiltering() {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = "key123"))

        assertTrue(plugin.isHallucination("(no text)"))
        assertTrue(plugin.isHallucination("no text"))
        assertTrue(plugin.isHallucination("(nessun testo)"))
        assertTrue(plugin.isHallucination("nessun testo"))
        assertTrue(plugin.isHallucination(""))
        assertTrue(plugin.isHallucination("   "))
        assertTrue(plugin.isHallucination("The image contains no text. The OCR result \"1\" is a hallucination"))
        assertTrue(plugin.isHallucination("text [0, 0, 999, 999](no text)"))
        assertTrue(plugin.isHallucination("[Non-Text]"))
        assertTrue(plugin.isHallucination("non-text"))
        assertTrue(plugin.isHallucination("[non text]"))
        assertTrue(plugin.isHallucination("[image]"))
        assertTrue(plugin.isHallucination("1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1"))
        assertTrue(plugin.isHallucination("111111111111111111111111111"))

        kotlin.test.assertFalse(plugin.isHallucination("Hello, how are you?"))
        kotlin.test.assertFalse(plugin.isHallucination("Questo è un testo valido."))
        kotlin.test.assertFalse(plugin.isHallucination("OHH!"))
        kotlin.test.assertFalse(plugin.isHallucination("VIKIR REALLY DID BRING THE CURE!"))
        kotlin.test.assertFalse(plugin.isHallucination("AHEUI'S\nSPOTS...!"))
        kotlin.test.assertFalse(plugin.isHallucination("HOW MANY PATIENTS DO WE HAVE?"))
        kotlin.test.assertFalse(plugin.isHallucination("hahahaha"))
    }

    @Test
    fun testTestLmStudioConnectionActionShowsToast() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(lmStudioUrl = "http://127.0.0.1:59999/v1"))
        val toasts = mutableListOf<String>()
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        io.mockk.every { context.showToast(any()) } answers {
            toasts.add(firstArg())
        }
        plugin.testApiConnection(ApiProvider.LM_STUDIO, context)
        assertTrue(toasts.isNotEmpty())
        assertTrue(toasts.first().contains("LM Studio"))
    }

    @Test
    fun testKoogHttpClientKtClassAvailable() {
        val clazz = Class.forName("ai.koog.http.client.ktor.KtorKoogHttpClientKt")
        assertNotNull(clazz)
    }

    @Test
    fun testGoogleLLMClientInitializationWithHttpClient() {
        val client = HttpClient()
        val googleClient = GoogleLLMClient(apiKey = "dummy-api-key", baseClient = client)
        assertNotNull(googleClient)
    }

    @Test
    fun testBuiltJarContainsKoogHttpClientKtorIfPresent() {
        val jarFile = File("build/libs/manhwaTranslatorAI.jar")
        if (jarFile.exists()) {
            ZipFile(jarFile).use { zip ->
                val entry = zip.getEntry("ai/koog/http/client/ktor/KtorKoogHttpClientKt.class")
                assertNotNull(entry, "KtorKoogHttpClientKt.class must be packaged in manhwaTranslatorAI.jar")
            }
        }
    }

    @Test
    fun testParseDictionaryUpdateResponseStandard() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val raw = """
            # CHILOMETRO / MODIFICHE APPORTATE
            - Rimosso dal buffer: Cap. 237 (spostato in archivio: "Virion crolla e la guerra è persa")
            - Aggiunto al buffer: Cap. 242
            - Variazioni Personaggi / Glossario: Aggiunto nuovo termine "Cuore di Drago"

            # NUOVO FILE DI CONTESTO
            ```markdown
            === LORE & GLOSSARIO FISSO ===
            mana = termine maschile ("il mana")
            Cuore di Drago = maschile ("il Cuore di Drago")

            === CRONOLOGIA REMOTA & ARCHIVIO ===
            - Cap. 237: Virion crolla e la guerra è persa

            === BUFFER EVENTI RECENTI ===
            - Cap. 238: Marcia nel deserto
            - Cap. 242: Arrivo al nuovo fronte

            === REGISTRO PERSONAGGI ATTIVI ===
            - Arthur Leywin: maschio, protagonista
            ```
        """.trimIndent()

        val parsed = service.parseDictionaryUpdateResponse(raw)
        assertTrue(parsed.changelog.contains("Rimosso dal buffer: Cap. 237"))
        assertTrue(parsed.changelog.contains("Aggiunto al buffer: Cap. 242"))
        assertFalse(parsed.changelog.contains("# CHILOMETRO"))
        assertTrue(parsed.updatedDictionary.contains("=== LORE & GLOSSARIO FISSO ==="))
        assertTrue(parsed.updatedDictionary.contains("Cuore di Drago = maschile"))
        assertTrue(parsed.updatedDictionary.contains("=== REGISTRO PERSONAGGI ATTIVI ==="))
        assertFalse(parsed.updatedDictionary.contains("```markdown"))
        assertFalse(parsed.updatedDictionary.contains("```"))
    }

    @Test
    fun testParseDictionaryUpdateResponseWithThinkingAndNoFences() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val raw = """
            <thought>
            Analyzing chapter 242. Need to slide the window.
            </thought>
            # CHANGELOG
            - Rimosso dal buffer: Cap. 236
            - Aggiunto al buffer: Cap. 241

            # NUOVO CONTESTO
            === LORE & GLOSSARIO FISSO ===
            mana = termine maschile

            === CRONOLOGIA REMOTA & ARCHIVIO ===
            - Evento remoto

            === BUFFER EVENTI RECENTI ===
            - Cap. 241: Reunion

            === REGISTRO PERSONAGGI ATTIVI ===
            - Tessia: femmina
        """.trimIndent()

        val parsed = service.parseDictionaryUpdateResponse(raw)
        assertFalse(parsed.changelog.contains("<thought>"))
        assertTrue(parsed.changelog.contains("Rimosso dal buffer: Cap. 236"))
        assertTrue(parsed.updatedDictionary.startsWith("=== LORE & GLOSSARIO FISSO ==="))
        assertTrue(parsed.updatedDictionary.contains("Tessia: femmina"))
    }

    @Test
    fun testFormatTranslationsTxtAndMarkdown() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val translations = listOf("Ciao mondo", "Come va?", "A presto!")
        val originals = listOf("Hello world", "How goes it?", "See you soon!")
        val pages = listOf("01.png", "01.png", "02.png")

        val txt = service.formatTranslations(
            translations = translations,
            originalTexts = originals,
            pageNames = pages,
            format = "txt"
        )
        assertTrue(txt.contains("PAGINA: 01.png"))
        assertTrue(txt.contains("PAGINA: 02.png"))
        assertTrue(txt.contains("[1] Ciao mondo"))
        assertTrue(txt.contains("[3] A presto!"))

        val md = service.formatTranslations(
            translations = translations,
            originalTexts = originals,
            pageNames = pages,
            format = "markdown"
        )
        assertTrue(md.contains("# Traduzione"))
        assertTrue(md.contains("## Pagina: 01.png"))
        assertTrue(md.contains("- **[Originale]**: Hello world"))
        assertTrue(md.contains("**[Italiano]**: Ciao mondo"))

        val bilingual = service.formatTranslations(
            translations = translations,
            originalTexts = originals,
            pageNames = pages,
            format = "bilingual"
        )
        assertTrue(bilingual.contains("TRADUZIONE BILINGUE"))
        assertTrue(bilingual.contains("ORIGINAL: Hello world"))
        assertTrue(bilingual.contains("ITALIAN : Ciao mondo"))

        val json = service.formatTranslations(
            translations = translations,
            originalTexts = originals,
            pageNames = pages,
            format = "json"
        )
        assertTrue(json.contains("\"totalCount\": 3"))
        assertTrue(json.contains("\"translation\": \"Ciao mondo\""))
    }

    @Test
    fun testSaveTranslationsCapabilities() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = "dummy-key"))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)
        io.mockk.coEvery { hostFs.createDirectory(any()) } answers {
            val path = firstArg<String>()
            File(path).mkdirs()
            Result.success(Unit)
        }
        io.mockk.coEvery { hostFs.writeTextFile(any(), any()) } answers {
            val path = firstArg<String>()
            val text = secondArg<String>()
            File(path).writeText(text)
            Result.success(Unit)
        }

        val tempDir = File.createTempFile("wom_test_save_", "")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val outFile = File(tempDir, "all_translations.txt")
            val resultPath = plugin.saveTranslations(
                translations = listOf("Uno", "Due"),
                originalTexts = listOf("One", "Two"),
                pageNames = listOf("01.png", "01.png"),
                outputFile = outFile.absolutePath,
                outputDir = tempDir.absolutePath,
                fileName = "all_translations.txt",
                format = "txt",
                context = context,
                hostFs = hostFs
            )

            assertEquals(outFile.absolutePath, resultPath)
            assertTrue(outFile.exists())
            val savedText = outFile.readText()
            assertTrue(savedText.contains("PAGINA: 01.png"))
            assertTrue(savedText.contains("[1] Uno"))
            assertTrue(savedText.contains("[2] Due"))

            val ocrResult = OCRResult(
                texts = listOf("Testo OCR"),
                bb = listOf(listOf(0.0, 0.0, 1.0, 1.0)),
                pageNumbers = listOf(1),
                pageNames = listOf("page_01.png"),
                failedFiles = emptyList()
            )

            val ocrOut = File(tempDir, "ocr_saved.txt")
            val ocrPath = plugin.saveOcrTranslations(
                ocrResult = ocrResult,
                outputFile = ocrOut.absolutePath,
                outputDir = tempDir.absolutePath,
                fileName = "ocr_saved.txt",
                format = "txt",
                context = context,
                hostFs = hostFs
            )
            assertEquals(ocrOut.absolutePath, ocrPath)
            assertTrue(ocrOut.exists())
            assertTrue(ocrOut.readText().contains("Testo OCR"))

            val advOcr = AdvancedOCRResult(
                texts = listOf("Testo Avanzato"),
                balloonBoxes = listOf(listOf(0.0, 0.0, 1.0, 1.0)),
                textBoxes = listOf(listOf(0.1, 0.1, 0.9, 0.9)),
                shapes = listOf("oval"),
                fontStyles = listOf("normal"),
                fontFamilies = listOf("anime"),
                textAngles = listOf(0.0),
                isSparse = listOf(false),
                textColors = listOf("#000"),
                hasBorder = listOf(false),
                borderColors = listOf("#fff"),
                pageNumbers = listOf(1),
                pageNames = listOf("adv_page_01.png"),
                failedFiles = emptyList()
            )
            val advOut = File(tempDir, "adv_saved.txt")
            val advPath = plugin.saveAdvancedOcrTranslations(
                ocrResult = advOcr,
                outputFile = advOut.absolutePath,
                outputDir = tempDir.absolutePath,
                fileName = "adv_saved.txt",
                format = "txt",
                context = context,
                hostFs = hostFs
            )
            assertEquals(advOut.absolutePath, advPath)
            assertTrue(advOut.exists())
            assertTrue(advOut.readText().contains("Testo Avanzato"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUpdateDictionaryOcrCapabilityValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val ocrResult = OCRResult(
            texts = listOf("Arthur: Stop!", "(no text)"),
            bb = listOf(listOf(0.0, 0.0, 1.0, 1.0), listOf(0.0, 0.0, 1.0, 1.0)),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("page_01.png", "page_01.png"),
            failedFiles = emptyList()
        )

        // Verifies that with empty API key, it throws proper error indicating missing API Key
        val exception = try {
            plugin.updateDictionaryOcr(
                currentDictionary = "",
                chapterSummary = "Sommario capitolo 242",
                chapterNumber = "242",
                originalOcr = ocrResult,
                translatedOcr = null,
                model = AIModel.GEMINI_3_8_FLASH,
                outputDir = "build/test_dict",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(exception.message?.contains("API Key not found") == true || exception.cause?.message?.contains("API Key not found") == true)
    }

    @Test
    fun testUpdateDictionaryAdvancedOcrCapabilityValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val advOcr = AdvancedOCRResult(
            texts = listOf("Arthur: Fermati!"),
            balloonBoxes = listOf(listOf(0.0, 0.0, 1.0, 1.0)),
            textBoxes = listOf(listOf(0.1, 0.1, 0.9, 0.9)),
            shapes = listOf("oval"),
            fontStyles = listOf("normal"),
            fontFamilies = listOf("anime"),
            textAngles = listOf(0.0),
            isSparse = listOf(false),
            textColors = listOf("#000"),
            hasBorder = listOf(false),
            borderColors = listOf("#fff"),
            pageNumbers = listOf(1),
            pageNames = listOf("adv_page_01.png"),
            failedFiles = emptyList()
        )

        val exception = try {
            plugin.updateDictionaryAdvancedOcr(
                currentDictionary = "",
                chapterSummary = "Sommario capitolo 242",
                chapterNumber = "242",
                originalOcr = null,
                translatedOcr = advOcr,
                model = AIModel.GEMINI_3_8_FLASH,
                outputFile = true,
                outputDir = "build/test_dict",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(exception.message?.contains("API Key not found") == true || exception.cause?.message?.contains("API Key not found") == true)
    }

    @Test
    fun testUpdateDictionaryCapabilityValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val exception = try {
            plugin.updateDictionary(
                currentDictionary = "",
                chapterSummary = "Sommario capitolo 242",
                chapterNumber = "242",
                originalTexts = listOf("Hello"),
                translatedTexts = listOf("Ciao"),
                model = AIModel.GEMINI_3_8_FLASH,
                outputFile = false,
                outputDir = "build/test_dict",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(exception.message?.contains("API Key not found") == true || exception.cause?.message?.contains("API Key not found") == true)
    }

    @Test
    fun testSaveDictionaryFilesSeparatedInOutputDir() = kotlinx.coroutines.runBlocking {
        val mockFs = io.mockk.mockk<HostFileSystem>(relaxed = true)
        io.mockk.coEvery { mockFs.createDirectory(any()) } answers {
            val path = firstArg<String>()
            File(path).mkdirs()
            Result.success(Unit)
        }
        io.mockk.coEvery { mockFs.writeTextFile(any(), any()) } answers {
            val path = firstArg<String>()
            val text = secondArg<String>()
            File(path).writeText(text)
            Result.success(Unit)
        }

        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            mockFs
        )

        val tempDir = File.createTempFile("wom_test_dict_save_", "")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val parsedResult = DictionaryUpdateResult(
                changelog = "- Aggiunto al buffer: Cap. 242\n- Modifiche: Virion in archivio",
                updatedDictionary = "=== LORE & GLOSSARIO FISSO ===\nmana = maschile\n\n=== BUFFER EVENTI RECENTI ===\n- Cap. 242: Arrivo"
            )

            val (dictPath, changelogPath) = service.saveDictionaryFiles(
                parsed = parsedResult,
                outputDir = tempDir.absolutePath,
                chapterNumber = "Cap. 242"
            )

            val dictFile = File(dictPath)
            val changelogFile = File(changelogPath)
            val chapterChangelogFile = File(tempDir, "changelog_Cap._242.md")
            val legacyDict = File(tempDir, "dizionario_aggiornato.md")
            val legacyChangelog = File(tempDir, "dizionario_changelog.txt")

            assertTrue(dictFile.exists(), "dictionary.md should exist in outputDir")
            assertEquals("dictionary.md", dictFile.name)
            assertTrue(dictFile.readText().contains("=== LORE & GLOSSARIO FISSO ==="))

            assertTrue(changelogFile.exists(), "changelog.md should exist in outputDir")
            assertEquals("changelog.md", changelogFile.name)
            assertTrue(changelogFile.readText().contains("Aggiunto al buffer: Cap. 242"))

            assertTrue(chapterChangelogFile.exists(), "Chapter-specific changelog file should exist")
            assertTrue(chapterChangelogFile.readText().contains("Virion in archivio"))

            assertTrue(legacyDict.exists(), "Legacy dizionario_aggiornato.md should exist")
            assertTrue(legacyChangelog.exists(), "Legacy dizionario_changelog.txt should exist")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDeepSeekModelIdentifiers() {
        assertEquals("deepseek-flash", AIModel.DEEPSEEK_FLASH.id)
        assertEquals("deepseek-v4-pro", AIModel.DEEPSEEK_PRO.id)
    }

    @Test
    fun testZaiModelIdentifiers() {
        assertEquals("glm-5.3-flash", AIModel.GLM_5_3_FLASH.id)
        assertEquals("glm-5.3-flashx", AIModel.GLM_5_3_FLASHX.id)
        assertEquals("glm-4.7-flash", AIModel.GLM_4_7_FLASH.id)
        assertEquals(AIModel.GLM_5_3_FLASH, AIModel.ZAI_GLM_5_3_FLASH)
        assertEquals(AIModel.GLM_5_3_FLASHX, AIModel.ZAI_GLM_5_3_FLASHX)
        assertEquals(AIModel.GLM_4_7_FLASH, AIModel.ZAI_GLM_4_7_FLASH)
        assertEquals(AIModel.GLM_5_3_FLASH, AIModel.fromId("glm-5.3-flash"))
        assertEquals(AIModel.GLM_5_3_FLASHX, AIModel.fromId("GLM-5.3-FLASHX"))
        assertEquals(AIModel.GLM_4_7_FLASH, AIModel.fromId("glm-4.7-flash"))
    }

    @Test
    fun testTranslatorAISettingsDeepSeekDefaults() {
        val settings = TranslatorAISettings()
        assertEquals("", settings.deepseekApiKey)
        assertEquals("https://api.deepseek.com", settings.deepseekBaseUrl)
    }

    @Test
    fun testTranslatorAISettingsZaiDefaults() {
        val settings = TranslatorAISettings()
        assertEquals("", settings.zaiApiKey)
        assertEquals("https://api.z.ai/api/paas/v4", settings.zaiBaseUrl)
    }

    @Test
    fun testReasoningEffortLevelMappings() {
        assertEquals(null, ReasoningEffortLevel.DEFAULT.toKoogOpenAIEffort())
        assertEquals(ReasoningEffort.LOW, ReasoningEffortLevel.LOW.toKoogOpenAIEffort())
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffortLevel.MEDIUM.toKoogOpenAIEffort())
        assertEquals(ReasoningEffort.HIGH, ReasoningEffortLevel.HIGH.toKoogOpenAIEffort())
    }

    @Test
    fun testDeepSeekMissingApiKeyValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(deepseekApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val exception = try {
            plugin.translate(
                input = listOf("Hello"),
                model = AIModel.DEEPSEEK_FLASH,
                inputFolder = "dummy",
                outputDir = "dummy/out",
                tempSummaryDir = "dummy/temp",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(
            exception.message?.contains("DeepSeek API Key") == true ||
            exception.cause?.message?.contains("DeepSeek API Key") == true,
            "Expected error to mention DeepSeek API Key, got: ${exception.message} / ${exception.cause?.message}"
        )
    }

    @Test
    fun testDeepSeekDictionaryUpdateMissingApiKeyValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(deepseekApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val exception = try {
            plugin.updateDictionary(
                currentDictionary = "",
                chapterSummary = "Sommario",
                chapterNumber = "1",
                originalTexts = listOf("Hello"),
                model = AIModel.DEEPSEEK_PRO,
                outputFile = false,
                outputDir = "build/test_dict",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(
            exception.message?.contains("DeepSeek API Key") == true ||
            exception.cause?.message?.contains("DeepSeek API Key") == true,
            "Expected error to mention DeepSeek API Key, got: ${exception.message} / ${exception.cause?.message}"
        )
    }

    @Test
    fun testZaiMissingApiKeyValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(zaiApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val exception = try {
            plugin.translate(
                input = listOf("Hello"),
                model = AIModel.GLM_5_3_FLASH,
                inputFolder = "dummy",
                outputDir = "dummy/out",
                tempSummaryDir = "dummy/temp",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(
            exception.message?.contains("Z.AI API Key") == true ||
            exception.cause?.message?.contains("Z.AI API Key") == true,
            "Expected error to mention Z.AI API Key, got: ${exception.message} / ${exception.cause?.message}"
        )
    }

    @Test
    fun testZaiDictionaryUpdateMissingApiKeyValidation() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(zaiApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val exception = try {
            plugin.updateDictionary(
                currentDictionary = "",
                chapterSummary = "Sommario",
                chapterNumber = "1",
                originalTexts = listOf("Hello"),
                model = AIModel.GLM_5_3_FLASHX,
                outputFile = false,
                outputDir = "build/test_dict",
                context = context,
                hostFs = hostFs
            )
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception)
        assertTrue(
            exception.message?.contains("Z.AI API Key") == true ||
            exception.cause?.message?.contains("Z.AI API Key") == true,
            "Expected error to mention Z.AI API Key, got: ${exception.message} / ${exception.cause?.message}"
        )
    }

    @Test
    fun testParseDictionaryUpdateResponseStripsThinkTags() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val rawWithThink = """
            <think>
            Here is internal reasoning from deepseek:
            Analyzing chapter 243 events and lore changes...
            </think>
            # CHILOMETRO / MODIFICHE APPORTATE
            - Aggiunto al buffer: Cap. 243
            - Variazioni: nessuna

            # NUOVO FILE DI CONTESTO
            ```markdown
            === LORE & GLOSSARIO FISSO ===
            mana = maschile
            ```
        """.trimIndent()

        val parsed = service.parseDictionaryUpdateResponse(rawWithThink)
        assertFalse(parsed.changelog.contains("internal reasoning"))
        assertFalse(parsed.changelog.contains("<think>"))
        assertTrue(parsed.changelog.contains("Aggiunto al buffer: Cap. 243"))
        assertFalse(parsed.updatedDictionary.contains("internal reasoning"))
        assertTrue(parsed.updatedDictionary.contains("=== LORE & GLOSSARIO FISSO ==="))
    }

    @Test
    fun testTestApiConnectionActionShowsToast() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(deepseekApiKey = "dummy-key", googleApiKey = "dummy-google"))
        val toasts = mutableListOf<String>()
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        io.mockk.every { context.showToast(any()) } answers {
            toasts.add(firstArg())
        }

        plugin.testApiConnection(ApiProvider.DEEPSEEK, context)
        assertEquals(1, toasts.size)
        assertTrue(toasts.last().contains("DeepSeek"))

        plugin.testApiConnection(ApiProvider.LM_STUDIO, context)
        assertEquals(2, toasts.size)
        assertTrue(toasts.last().contains("LM Studio"))

        plugin.testApiConnection(ApiProvider.ZAI, context)
        assertEquals(3, toasts.size)
        assertTrue(toasts.last().contains("Z.AI"))
    }

    @Test
    fun testExtractJsonWithDeepSeekTrailingCommentary() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val outputWithCommentary = """
            {
              "translations": [
                "Prima riga tradotta.",
                "Seconda riga con \"virgolette\" e {parentesi} nel testo."
              ]
            }

            Count 2. Good.

            But let's consider final checks:
            1 E ...
        """.trimIndent()

        val extracted = service.extractJson(outputWithCommentary)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<TranslationResponse>(extracted)

        assertEquals(2, parsed.translations.size)
        assertEquals("Prima riga tradotta.", parsed.translations[0])
        assertEquals("Seconda riga con \"virgolette\" e {parentesi} nel testo.", parsed.translations[1])
    }

    @Test
    fun testExtractJsonWithPreambleAndFences() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val outputWithPreamble = """
            We need answer only JSON with
            ```json
            {
              "translations": [
                "Testo uno",
                "Testo due"
              ]
            }
            ```
            Hope this helps!
        """.trimIndent()

        val extracted = service.extractJson(outputWithPreamble)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<TranslationResponse>(extracted)

        assertEquals(2, parsed.translations.size)
        assertEquals("Testo uno", parsed.translations[0])
        assertEquals("Testo due", parsed.translations[1])
    }

    @Test
    fun testParseTranslationsResponseWithRawArray() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val rawArrayOutput = """
            Here is your translation:
            ```json
            [
              "Prima frase",
              "Seconda frase"
            ]
            ```
        """.trimIndent()

        val results = service.parseTranslationsResponse(rawArrayOutput, expectedCount = 2)
        assertEquals(2, results.size)
        assertEquals("Prima frase", results[0])
        assertEquals("Seconda frase", results[1])
    }

    @Test
    fun testParseTranslationsResponseWithAlternativeKeys() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val resultOutput = """
            {
              "results": [
                "Risultato uno",
                "Risultato due"
              ]
            }
        """.trimIndent()

        val results = service.parseTranslationsResponse(resultOutput, expectedCount = 2)
        assertEquals(2, results.size)
        assertEquals("Risultato uno", results[0])
        assertEquals("Risultato due", results[1])
    }

    @Test
    fun testParseTranslationsResponseWithObjectElements() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val objectElementsOutput = """
            {
              "translations": [
                {"text": "Oggetto uno"},
                {"text": "Oggetto due"}
              ]
            }
        """.trimIndent()

        val results = service.parseTranslationsResponse(objectElementsOutput, expectedCount = 2)
        assertEquals(2, results.size)
        assertEquals("Oggetto uno", results[0])
        assertEquals("Oggetto due", results[1])
    }

    @Test
    fun testChunkSizeSetting() {
        val defaultSettings = TranslatorAISettings()
        assertEquals(25, defaultSettings.chunkSize)

        val customSettings = TranslatorAISettings(chunkSize = 15)
        assertEquals(15, customSettings.chunkSize)
    }

    @Test
    fun testDebugLoggingSetting() {
        val defaultSettings = TranslatorAISettings()
        assertEquals(false, defaultSettings.debugLogging)

        val customSettings = TranslatorAISettings(debugLogging = true)
        assertEquals(true, customSettings.debugLogging)
    }

    @Test
    fun testExtractJsonWithPreambleBracketsAndLabels() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val outputWithPreambleBrackets = """
            [TEXT]: 354
            [354? Actually string is "■ ■ ■ ■ ■ ■"]
            Here is the result:
            {
              "translations": [
                "354",
                "■ ■ ■ ■ ■ ■",
                "Il mio corpo si sente più leggero...!"
              ]
            }
            Count 3. Done.
        """.trimIndent()

        val results = service.parseTranslationsResponse(outputWithPreambleBrackets, expectedCount = 3)
        assertEquals(3, results.size)
        assertEquals("354", results[0])
        assertEquals("■ ■ ■ ■ ■ ■", results[1])
        assertEquals("Il mio corpo si sente più leggero...!", results[2])
    }

    @Test
    fun testParseTranslationsResponseHandlesShrunkHallucinationsAndNulls() {
        val service = KoogAITranslatorService(
            io.mockk.mockk<PluginContext>(relaxed = true),
            TranslatorAISettings(),
            io.mockk.mockk<HostFileSystem>(relaxed = true)
        )

        val jsonWithShrunkItems = """
            {
              "translations": [
                "Primo testo",
                "",
                null,
                "Quarto testo"
              ]
            }
        """.trimIndent()

        val results = service.parseTranslationsResponse(jsonWithShrunkItems, expectedCount = 4)
        assertEquals(4, results.size)
        assertEquals("Primo testo", results[0])
        assertEquals("", results[1])
        assertEquals("", results[2])
        assertEquals("Quarto testo", results[3])
    }

    @Test
    fun testUseStructuredOutputParameterOverride() {
        val plugin = TranslatorAI(TranslatorAISettings(useStructuredOutput = false))
        assertEquals(false, plugin.settings.useStructuredOutput)

        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        // When structuredOutput is explicitly passed as ENABLED to the action, it overrides the setting
        val result = kotlinx.coroutines.runBlocking {
            plugin.translate(
                input = emptyList(),
                model = AIModel.GEMINI_3_5_FLASH,
                inputFolder = "dummy",
                outputDir = "dummy/out",
                tempSummaryDir = "dummy/temp",
                structuredOutput = StructuredOutputMode.ENABLED,
                context = context,
                hostFs = hostFs
            )
        }
        assertTrue(result.isEmpty())

        assertEquals("Default (Use Settings)", StructuredOutputMode.DEFAULT.displayName)
        assertEquals("Enabled", StructuredOutputMode.ENABLED.displayName)
        assertEquals("Disabled", StructuredOutputMode.DISABLED.displayName)
    }

    @Test
    fun testTranslateAllHallucinationsPreservesLengthWithoutApiCall() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = "dummy-key"))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val input = listOf("[Non-Text]", "1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1")
        val translations = plugin.translate(
            input = input,
            model = AIModel.GEMINI_3_5_FLASH,
            inputFolder = "dummy",
            outputDir = "dummy/out",
            tempSummaryDir = "dummy/temp",
            save = false,
            context = context,
            hostFs = hostFs
        )
        assertEquals(2, translations.size)
        assertEquals("", translations[0])
        assertEquals("", translations[1])
    }

    @Test
    fun testLiveDeepSeekSmallTranslation() = kotlinx.coroutines.runBlocking {
        var apiKey = (System.getenv("DEEPSEEK_API_KEY") ?: "").trim()
        if (apiKey.isBlank()) {
            val envFile = listOf(File("../.env"), File(".env")).firstOrNull { it.exists() }
            if (envFile != null) {
                val props = java.util.Properties()
                envFile.inputStream().use { props.load(it) }
                apiKey = (props.getProperty("DEEPSEEK_API_KEY") ?: "").trim()
            }
        }

        if (apiKey.isBlank()) {
            println("[LiveTest] Skipping testLiveDeepSeekSmallTranslation: DEEPSEEK_API_KEY is not set.")
            return@runBlocking
        }

        println("[LiveTest] Executing live DeepSeek test with 2 strings (thinking disabled, structured output enabled)...")
        val plugin = TranslatorAI(TranslatorAISettings(deepseekApiKey = apiKey))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val input = listOf("Hello world", "Good morning")
        val translations = plugin.translate(
            input = input,
            model = AIModel.DEEPSEEK_FLASH,
            inputFolder = "dummy",
            outputDir = "dummy/out",
            tempSummaryDir = "dummy/temp",
            enableThinking = false,
            structuredOutput = StructuredOutputMode.ENABLED,
            context = context,
            hostFs = hostFs
        )

        println("[LiveTest] Live DeepSeek translation response: $translations")
        assertEquals(2, translations.size)
        assertTrue(translations[0].isNotBlank(), "Translation 1 should not be blank")
        assertTrue(translations[1].isNotBlank(), "Translation 2 should not be blank")
    }

    @Test
    fun testLiveZaiSmallTranslation() = kotlinx.coroutines.runBlocking {
        var apiKey = (System.getenv("ZAI_API_KEY") ?: "").trim()
        if (apiKey.isBlank()) {
            val envFile = listOf(File("../.env"), File(".env")).firstOrNull { it.exists() }
            if (envFile != null) {
                val props = java.util.Properties()
                envFile.inputStream().use { props.load(it) }
                apiKey = (props.getProperty("ZAI_API_KEY") ?: "").trim()
            }
        }

        if (apiKey.isBlank()) {
            println("[LiveTest] Skipping testLiveZaiSmallTranslation: ZAI_API_KEY is not set.")
            return@runBlocking
        }

        println("[LiveTest] Executing live Z.AI test with GLM-4.7-Flash (2 strings, thinking disabled, structured output enabled)...")
        val plugin = TranslatorAI(TranslatorAISettings(zaiApiKey = apiKey))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val input = listOf("Hello world", "Good morning")
        val translations = plugin.translate(
            input = input,
            model = AIModel.GLM_4_7_FLASH,
            inputFolder = "dummy",
            outputDir = "dummy/out",
            tempSummaryDir = "dummy/temp",
            enableThinking = false,
            structuredOutput = StructuredOutputMode.ENABLED,
            context = context,
            hostFs = hostFs
        )

        println("[LiveTest] Live Z.AI translation response: $translations")
        assertEquals(2, translations.size)
        assertTrue(translations[0].isNotBlank(), "Translation 1 should not be blank")
        assertTrue(translations[1].isNotBlank(), "Translation 2 should not be blank")
    }

    @Test
    fun testUpdateDictionaryChapterNumberNumericPrimitive() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val ocrResult = OCRResult(
            texts = listOf("Test text"),
            bb = emptyList(),
            pageNumbers = emptyList(),
            pageNames = emptyList(),
            failedFiles = emptyList()
        )

        val ex1 = org.junit.Assert.assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking {
                plugin.updateDictionaryOcr(
                    currentDictionary = "",
                    chapterSummary = "Sommario capitolo 52",
                    chapterNumber = kotlinx.serialization.json.JsonPrimitive(52),
                    originalOcr = ocrResult,
                    translatedOcr = null,
                    model = AIModel.GEMINI_3_8_FLASH,
                    outputDir = "build/test_dict",
                    context = context,
                    hostFs = hostFs
                )
            }
        }
        assertFalse(ex1.message!!.contains("String literal for value of key 'primitive'"))

        val ex2 = org.junit.Assert.assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking {
                plugin.updateDictionary(
                    currentDictionary = "",
                    chapterSummary = "Sommario capitolo 52",
                    chapterNumber = kotlinx.serialization.json.JsonPrimitive(52),
                    model = AIModel.GEMINI_3_8_FLASH,
                    outputDir = "build/test_dict",
                    context = context,
                    hostFs = hostFs
                )
            }
        }
        assertFalse(ex2.message!!.contains("String literal for value of key 'primitive'"))
    }

    @Test
    fun testTargetClassesFilteringPreservesUntranslatedItems() = kotlinx.coroutines.runBlocking {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = ""))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val ocr = OCRResult(
            texts = listOf("Non matching text"),
            bb = listOf(listOf(0.1, 0.1, 0.2, 0.2)),
            pageNumbers = listOf(1),
            pageNames = listOf("p1.png"),
            failedFiles = emptyList(),
            categories = listOf("none")
        )

        // When target_classes only has speech and sfx, an item with category "none" should be skipped and returned untranslated without calling the AI
        val result = plugin.translateOcr(
            inputOcr = ocr,
            dictionary = "",
            model = AIModel.GEMMA_31B,
            inputFolder = "",
            outputDir = "build/test_out",
            tempSummaryDir = "build/test_temp",
            target_classes = listOf(TranslationTargetClass.speech, TranslationTargetClass.sfx),
            context = context,
            hostFs = hostFs
        )

        assertEquals(1, result.texts.size)
        assertEquals("Non matching text", result.texts[0])
        assertEquals("none", result.categories[0])
    }
}

