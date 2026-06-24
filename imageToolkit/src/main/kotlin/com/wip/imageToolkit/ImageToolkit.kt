package com.wip.imageToolkit

import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.CapabilityFileAccess
import org.wip.plugintoolkit.api.HostFileSystem
import org.openpdf.text.*
import org.openpdf.text.pdf.*
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO
import java.awt.Color

@PluginInfo(
    id = "com.wip.imageToolkit",
    name = "Image Toolkit",
    version = "1.1.2",
    description = "A plugin that provides toolkit to work with images."
)
class ImageToolkit {

    @Capability(
        name = "Add Text to Image",
        description = "Adds text to an image and saves as a layered PDF with vector text"
    )
    @CapabilityFileAccess(readsFiles = true, writesFiles = true)
    suspend fun addTextToImage(
        @CapabilityParam(description = "Path to image", semanticTypes = ["path/file"]) imagePath: String,
        @CapabilityParam(description = "Texts to add") texts: List<String>,
        @CapabilityParam(
            description = "Bounding boxes to add, (xmin, ymin, xmax, ymax)",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Font size (optional)", defaultValue = "20") fontSize: Int? = 20,
        @CapabilityParam(description = "Font name (optional)", defaultValue = "Arial") fontName: String? = "Helvetica",
        @CapabilityParam(description = "Page number (optional)") pageNumber: Int? = null,
        @CapabilityParam(description = "Page name (optional)") pageName: String? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val logger = context.logger
        logger.info("Starting addTextToImage for $imagePath")
        logger.debug("Texts: $texts, Bounding Boxes: $bb")
        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Image file not found: $imagePath")
        }

        val outputPdfPath = inputFile.absolutePath.substringBeforeLast(".") + "_layered.pdf"
        generateLayeredPdf(
            imagePath = imagePath,
            texts = texts,
            bb = bb,
            outputPdfPath = outputPdfPath,
            fontSize = fontSize ?: 20,
            fontName = fontName ?: "Helvetica",
            pageNumber = pageNumber,
            pageName = pageName,
            context = context
        )

        logger.info("Layered PDF saved to: $outputPdfPath")
        return outputPdfPath
    }

    @Capability(
        name = "Add Text to Chapter",
        description = "Adds text to a folder of images and saves as layered PDFs in an output directory"
    )
    @CapabilityFileAccess(readsFiles = true, writesFiles = true)
    suspend fun addTextToChapter(
        @CapabilityParam(
            description = "Path to folder of images",
            semanticTypes = ["path/folder"]
        ) inputFolder: String,
        @CapabilityParam(description = "Texts to add") texts: List<String>,
        @CapabilityParam(
            description = "Bounding boxes to add, (xmin, ymin, xmax, ymax)",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Page names corresponding to each text") pageNames: List<String>,
        @CapabilityParam(description = "Output directory", semanticTypes = ["path/folder"]) outputDir: String,
        @CapabilityParam(description = "Font size (optional)", defaultValue = "20") fontSize: Int? = 20,
        @CapabilityParam(description = "Font name (optional)", defaultValue = "Arial") fontName: String? = "Helvetica",
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val logger = context.logger
        logger.info("Starting addTextToChapter for $inputFolder")
        val progressReporter = context.progress
        
        val folder = File(inputFolder)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Input folder not found or is not a directory: $inputFolder")
        }

        val outDir = File(outputDir).apply { mkdirs() }

        // Group data by page name safely, handling potential size mismatches
        val minSize = minOf(texts.size, bb.size, pageNames.size)
        if (minSize != texts.size || minSize != bb.size || minSize != pageNames.size) {
            logger.warn("Size mismatch in Add Text to Chapter inputs: texts (${texts.size}), bb (${bb.size}), pageNames (${pageNames.size}). Truncating to $minSize.")
        }
        val groupedData = (0 until minSize).groupBy { pageNames[it] }
        val totalPages = groupedData.size
        var processedPages = 0

        groupedData.forEach { (pageName, indices) ->
            val imageFile = File(folder, pageName)
            if (imageFile.exists()) {
                logger.info("Processing page: $pageName")
                val outputPdfPath = File(outDir, pageName.substringBeforeLast(".") + ".pdf").absolutePath
                
                val pageTexts = indices.map { texts[it] }
                val pageBb = indices.map { bb[it] }

                generateLayeredPdf(
                    imagePath = imageFile.absolutePath,
                    texts = pageTexts,
                    bb = pageBb,
                    outputPdfPath = outputPdfPath,
                    fontSize = fontSize ?: 20,
                    fontName = fontName ?: "Helvetica",
                    pageNumber = null, // We could potentially infer this if needed
                    pageName = pageName,
                    context = context
                )
            } else {
                logger.warn("Image file not found for page name: $pageName")
            }
            processedPages++
            progressReporter.report(processedPages.toFloat() / totalPages.toFloat())
        }

        logger.info("Processed $processedPages pages to ${outDir.absolutePath}")
        return outDir.absolutePath
    }

    private fun generateLayeredPdf(
        imagePath: String,
        texts: List<String>,
        bb: List<List<Double>>,
        outputPdfPath: String,
        fontSize: Int,
        fontName: String,
        pageNumber: Int?,
        pageName: String?,
        context: PluginContext
    ) {
        val logger = context.logger
        logger.debug("Generating layered PDF: $outputPdfPath")
        val baseImage = ImageIO.read(File(imagePath))
        val width = baseImage.width.toFloat()
        val height = baseImage.height.toFloat()
        logger.info("Base image loaded: $imagePath (${width.toInt()}x${height.toInt()})")

        val document = Document(Rectangle(width, height))
        val writer = PdfWriter.getInstance(document, FileOutputStream(outputPdfPath))
        
        document.open()
        logger.debug("PDF document opened")
        val cb = writer.directContent

        // Layer 1: Background Image
        val bgLayer = PdfLayer("Background", writer)
        cb.beginLayer(bgLayer)
        val img = Image.getInstance(imagePath)
        img.setAbsolutePosition(0f, 0f)
        cb.addImage(img)
        cb.endLayer()
        logger.debug("Background layer added")

        // Layer 2: Text Layers
        val textLayer = PdfLayer("Text", writer)
        cb.beginLayer(textLayer)
        
        logger.info("Adding ${texts.size} text elements")
        val baseFont = try {
            BaseFont.createFont(fontName, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
        } catch (e: Exception) {
            logger.warn("Font $fontName not found, falling back to Helvetica")
            BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
        }

        texts.zip(bb).forEach { (text, box) ->
            val xmin = box[0] * width
            val ymin = (1.0 - box[3]) * height
            val xmax = box[2] * width
            val ymax = (1.0 - box[1]) * height

            val boxWidth = (xmax - xmin).toFloat()
            val boxHeight = (ymax - ymin).toFloat()

            cb.beginText()
            cb.setFontAndSize(baseFont, fontSize.toFloat())
            cb.setColorFill(Color.BLACK)
            
            val textWidth = baseFont.getWidthPoint(text, fontSize.toFloat())
            val xPos = xmin.toFloat() + (boxWidth - textWidth) / 2f
            val yPos = ymin.toFloat() + (boxHeight - fontSize.toFloat()) / 2f

            cb.showTextAligned(PdfContentByte.ALIGN_LEFT, text, xPos, yPos, 0f)
            cb.endText()
        }
        cb.endLayer()

        // Layer 3: Bounding Boxes (Optional/Debug)
        val bbLayer = PdfLayer("Bounding Box", writer)
        bbLayer.isOn = false // Hidden by default
        cb.beginLayer(bbLayer)
        cb.setLineWidth(1f)
        cb.setColorStroke(Color.RED)
        bb.forEach { box ->
            val xmin = (box[0] * width).toFloat()
            val ymin = ((1.0 - box[3]) * height).toFloat()
            val xmax = (box[2] * width).toFloat()
            val ymax = ((1.0 - box[1]) * height).toFloat()
            cb.rectangle(xmin, ymin, xmax - xmin, ymax - ymin)
            cb.stroke()
        }
        cb.endLayer()

        // Layer 4: Page Information
        if (pageNumber != null || pageName != null) {
            val infoLayer = PdfLayer("Page Information", writer)
            cb.beginLayer(infoLayer)
            cb.beginText()
            cb.setFontAndSize(baseFont, 12f)
            cb.setColorFill(Color.BLUE)
            val infoText = buildString {
                if (pageNumber != null) append("Page: $pageNumber ")
                if (pageName != null) append("($pageName)")
            }.trim()
            // Place at top-left with some margin
            cb.showTextAligned(PdfContentByte.ALIGN_LEFT, infoText, 10f, height - 20f, 0f)
            cb.endText()
            cb.endLayer()
        }

        document.close()
        logger.info("PDF generation complete: $outputPdfPath")
    }
}
