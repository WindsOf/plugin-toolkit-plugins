package com.wip.common.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelManifestTest {

    @Test
    fun testParseYoloV10Yaml() {
        val yaml = """
            type: yolov10
            name: yolo-det-x-best-v3
            display_name: Yolo-Det-X-Best-V3
            model_path: yolo-det-x-best-v3.onnx
            input_width: 640
            input_height: 640
            score_threshold: 0.25
            conf_threshold: 0.25
            iou_threshold: 0.45
            classes:
            - balloon
            - text
            - watermark
        """.trimIndent()

        val spec = ModelSpec.parseFromYaml(yaml)
        assertEquals("yolov10", spec.type)
        assertEquals(ModelType.YOLO_V10, spec.modelType)
        assertEquals("yolo-det-x-best-v3", spec.name)
        assertEquals("Yolo-Det-X-Best-V3", spec.displayName)
        assertEquals("yolo-det-x-best-v3.onnx", spec.modelPath)
        assertEquals(640, spec.inputWidth)
        assertEquals(640, spec.inputHeight)
        assertEquals(0.25, spec.scoreThreshold)
        assertEquals(3, spec.classes.size)
        assertTrue(spec.classes.contains("balloon"))
        assertTrue(spec.classes.contains("text"))
        assertTrue(spec.classes.contains("watermark"))
    }

    @Test
    fun testParseRfdetrSegYaml() {
        val yaml = """
            type: rfdetr_seg
            name: rfdetr-seg-2xlarge-ema-v3
            display_name: Rfdetr-Seg-2Xlarge-Ema-V3
            model_path: rfdetr-seg-2xlarge-ema-v3.onnx
            input_width: 768
            input_height: 768
            score_threshold: 0.25
            conf_threshold: 0.25
            iou_threshold: 0.45
            classes:
            - circular
            - irregular
            - jagged
            - rectangular
            - spiky
            - text
            - watermark
        """.trimIndent()

        val spec = ModelSpec.parseFromYaml(yaml)
        assertEquals("rfdetr_seg", spec.type)
        assertEquals(ModelType.RFDETR_SEG, spec.modelType)
        assertEquals("rfdetr-seg-2xlarge-ema-v3", spec.name)
        assertEquals(768, spec.inputWidth)
        assertEquals(7, spec.classes.size)
    }

    @Test
    fun testModelCatalogEntries() {
        val yolo = ModelCatalog.findById("yolo-det-x-best-v3")
        assertNotNull(yolo)
        assertEquals("model:yolo-det-x-best-v3", yolo.lockKey)
        assertEquals(ModelType.YOLO_V10, yolo.type)

        val rfdetr = ModelCatalog.findById("rfdetr-seg-2xlarge-ema-v3")
        assertNotNull(rfdetr)
        assertEquals("model:rfdetr-seg-2xlarge-ema-v3", rfdetr.lockKey)
        assertEquals(ModelType.RFDETR_SEG, rfdetr.type)

        val lama = ModelCatalog.findById("lama")
        assertNotNull(lama)
        assertEquals("model:big-lama", lama.lockKey)
        assertEquals(ModelType.INPAINTING, lama.type)
        assertEquals("https://www.windsofresub.cloud/models/big-lama.yaml", lama.yamlUrl)
        assertEquals("https://www.windsofresub.cloud/models/big-lama.onnx", lama.onnxUrl)

        val mat = ModelCatalog.findById("mat")
        assertNotNull(mat)
        assertEquals("model:Places_512_FullData_G", mat.lockKey)

        val fcf = ModelCatalog.findById("fcf")
        assertNotNull(fcf)
        assertEquals("model:places_512_G", fcf.lockKey)

        val zits = ModelCatalog.findById("zits")
        assertNotNull(zits)
        assertEquals("model:zits-inpaint-0717", zits.lockKey)

        val diffusion = ModelCatalog.findById("ldm")
        assertNotNull(diffusion)
        assertEquals("model:diffusion", diffusion.lockKey)

        val ocr = ModelCatalog.findById("Unlimited-OCR")
        assertNotNull(ocr)
        assertEquals("model:Unlimited-OCR", ocr.lockKey)
        assertEquals("https://www.windsofresub.cloud/models/Unlimited-OCR.yaml", ocr.yamlUrl)
        assertEquals("https://www.windsofresub.cloud/models/Unlimited-OCR.onnx", ocr.onnxUrl)
        assertEquals(ModelType.OCR, ocr.type)
        assertEquals("onnx", ocr.format)

        val ocrBf16 = ModelCatalog.findById("Unlimited-OCR-BF16")
        assertNotNull(ocrBf16)
        assertEquals("model:Unlimited-OCR-BF16", ocrBf16.lockKey)
        assertEquals(ModelType.OCR, ocrBf16.type)
        assertEquals("onnx", ocrBf16.format)

        val ocrQ4 = ModelCatalog.findById("Unlimited-OCR-Q4_K_M")
        assertNotNull(ocrQ4)
        assertEquals(ModelType.OCR, ocrQ4.type)
        assertEquals("gguf", ocrQ4.format)

        val ocrIq2 = ModelCatalog.findById("Unlimited-OCR-IQ2_M")
        assertNotNull(ocrIq2)
        assertEquals(ModelType.OCR, ocrIq2.type)
        assertEquals("gguf", ocrIq2.format)

        assertEquals(13, ModelCatalog.ALL_MODELS.size)
    }

    @Test
    fun testParseUnlimitedOcrBf16Yaml() {
        val ocrYaml = """
            name: Unlimited-OCR-BF16
            display_name: Unlimited-OCR (BF16)
            model_type: deepseek_ocr_decoder
            format: onnx
            onnx_file: Unlimited-OCR-BF16.onnx
            data_file: Unlimited-OCR-BF16.onnx.data
            external_data: true
            vocab_size: 129280
            hidden_size: 2048
            num_layers: 24
            dynamic_axes: true
            classes:
            - text
        """.trimIndent()

        val spec = ModelSpec.parseFromYaml(ocrYaml)
        assertEquals("deepseek_ocr_decoder", spec.effectiveType)
        assertEquals(ModelType.OCR, spec.modelType)
        assertEquals("onnx", spec.format)
        assertEquals("Unlimited-OCR-BF16.onnx", spec.onnxFile)
        assertEquals("Unlimited-OCR-BF16.onnx.data", spec.dataFile)
        assertTrue(spec.externalData)
        assertEquals(129280, spec.vocabSize)
        assertEquals(2048, spec.hiddenSize)
        assertEquals(24, spec.numLayers)
        assertTrue(spec.dynamicAxes)

        val requiredFiles = spec.getRequiredFileNames("Unlimited-OCR-BF16")
        assertEquals(listOf("Unlimited-OCR-BF16.onnx", "Unlimited-OCR-BF16.onnx.data"), requiredFiles)
    }

    @Test
    fun testParseGgufYamlWithFilesMap() {
        val ggufYaml = """
            name: Unlimited-OCR-Q4_K_M
            model_type: ocr
            format: gguf
            files:
              model: Unlimited-OCR-Q4_K_M.gguf
            vocab_size: 129280
            hidden_size: 2048
        """.trimIndent()

        val spec = ModelSpec.parseFromYaml(ggufYaml)
        assertEquals(ModelType.OCR, spec.modelType)
        assertEquals("gguf", spec.format)
        assertEquals(mapOf("model" to "Unlimited-OCR-Q4_K_M.gguf"), spec.files)

        val requiredFiles = spec.getRequiredFileNames("Unlimited-OCR-Q4_K_M")
        assertEquals(listOf("Unlimited-OCR-Q4_K_M.gguf"), requiredFiles)
    }

    @Test
    fun testParseInpaintingYaml() {
        val lamaYaml = """
            model_type: lama
            pipeline_type: single_pass
            description: Resolution-robust Large Mask Inpainting with Fast Fourier Convolutions
            repo: https://github.com/saic-mdal/lama
            input_resolution:
            - 512
            - 512
            dynamic_shape: true
            norm_mode: zero_to_one
            mask_mode: zero_to_one
            input_names:
            - image
            - mask
            output_names:
            - output
            opset_version: 17
        """.trimIndent()

        val spec = ModelSpec.parseFromYaml(lamaYaml)
        assertEquals("lama", spec.effectiveType)
        assertEquals("single_pass", spec.pipelineType)
        assertEquals(ModelType.INPAINTING, spec.modelType)
        assertEquals(512, spec.effectiveWidth)
        assertEquals(512, spec.effectiveHeight)
        assertTrue(spec.dynamicShape)
        assertEquals(listOf("image", "mask"), spec.inputNames)
        assertEquals(listOf("output"), spec.outputNames)
    }

    @Test
    fun testParseDiffusionYaml() {
        val diffYaml = """
            model_type: ldm
            pipeline_type: diffusion_pipeline
            description: Latent Diffusion Models for High-Resolution Inpainting
            repo: https://github.com/CompVis/latent-diffusion
            input_resolution:
            - 256
            - 256
            dynamic_shape: true
            norm_mode: neg_one_to_one
            mask_mode: zero_to_one
            input_names:
            - sample
            - timestep_embed
            - condition_concat
            output_names:
            - noise_pred
            opset_version: 17
            pipeline_config:
              num_timesteps: 1000
              default_inference_steps: 50
              beta_schedule: linear
              channels: 3
              embed_dim: 256
        """.trimIndent()

        val spec = ModelSpec.parseFromYaml(diffYaml)
        assertEquals("ldm", spec.effectiveType)
        assertEquals("diffusion_pipeline", spec.pipelineType)
        assertEquals(256, spec.effectiveWidth)
        assertEquals(256, spec.effectiveHeight)
        assertEquals(listOf("sample", "timestep_embed", "condition_concat"), spec.inputNames)
        assertEquals(listOf("noise_pred"), spec.outputNames)
        assertEquals(1000, spec.pipelineConfig.numTimesteps)
        assertEquals(50, spec.pipelineConfig.defaultInferenceSteps)
        assertEquals("linear", spec.pipelineConfig.betaSchedule)
    }
}
