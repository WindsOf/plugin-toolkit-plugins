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

        assertEquals(2, ModelCatalog.ALL_MODELS.size)
    }
}
