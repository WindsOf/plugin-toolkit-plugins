package com.wip.kpsd

@Suppress("UNCHECKED_CAST")
object TextLayer {

    private val defaultFont = Font(name = "MyriadPro-Regular", script = 0, type = 0, synthetic = 0)

    private val defaultParagraphStyle = ParagraphStyle(
        justification = "left",
        firstLineIndent = 0f,
        startIndent = 0f,
        endIndent = 0f,
        spaceBefore = 0f,
        spaceAfter = 0f,
        autoHyphenate = true,
        hyphenatedWordSize = 6,
        preHyphen = 2,
        postHyphen = 2,
        consecutiveHyphens = 8,
        zone = 36f,
        wordSpacing = floatArrayOf(0.8f, 1.0f, 1.33f),
        letterSpacing = floatArrayOf(0.0f, 0.0f, 0.0f),
        glyphSpacing = floatArrayOf(1.0f, 1.0f, 1.0f),
        autoLeading = 1.2f,
        leadingType = 0,
        hanging = false,
        burasagari = false,
        kinsokuOrder = 0,
        everyLineComposer = false
    )

    private val defaultStyle = TextStyle(
        font = defaultFont,
        fontSize = 12f,
        fauxBold = false,
        fauxItalic = false,
        autoLeading = true,
        leading = 0f,
        horizontalScale = 1f,
        verticalScale = 1f,
        tracking = 0f,
        autoKerning = true,
        kerning = 0f,
        baselineShift = 0f,
        fontCaps = 0,
        fontBaseline = 0,
        underline = false,
        strikethrough = false,
        ligatures = true,
        dLigatures = false,
        baselineDirection = 2,
        tsume = 0f,
        styleRunAlignment = 2,
        language = 0,
        noBreak = false,
        fillColor = Rgb(0, 0, 0),
        strokeColor = Rgb(0, 0, 0),
        fillFlag = true,
        strokeFlag = false,
        fillFirst = true,
        yUnderline = 1,
        outlineWidth = 1f,
        characterDirection = 0,
        hindiNumbers = false,
        kashida = 1f,
        diacriticPos = 2
    )

    private val defaultGridInfo = TextGridInfo(
        isOn = false,
        show = false,
        size = 18f,
        leading = 22f,
        color = Rgb(0, 0, 255),
        leadingFillColor = Rgb(0, 0, 255),
        alignLineHeightToGridFlags = false
    )

    private val antialias = listOf("none", "crisp", "strong", "smooth", "sharp")
    private val justification = listOf("left", "right", "center", "justify-left", "justify-right", "justify-center", "justify-all")

    private fun decodeColor(color: Map<String, Any?>): Color {
        val type = (color["Type"] as? Number)?.toInt() ?: 1
        val values = color["Values"] as? List<Number> ?: listOf(1.0, 0.0, 0.0, 0.0)
        return when (type) {
            0 -> GrayscaleColor((values[1].toFloat() * 255).toInt())
            1 -> {
                if (values[0].toFloat() == 1.0f) {
                    Rgb((values[1].toFloat() * 255).toInt(), (values[2].toFloat() * 255).toInt(), (values[3].toFloat() * 255).toInt())
                } else {
                    Rgba(
                        (values[1].toFloat() * 255).toInt(),
                        (values[2].toFloat() * 255).toInt(),
                        (values[3].toFloat() * 255).toInt(),
                        (values[0].toFloat() * 255).toInt()
                    )
                }
            }
            2 -> Cmyk(
                (values[1].toFloat() * 255).toInt(),
                (values[2].toFloat() * 255).toInt(),
                (values[3].toFloat() * 255).toInt(),
                (values[4].toFloat() * 255).toInt()
            )
            else -> throw IllegalArgumentException("Unknown color type in text layer")
        }
    }

    private fun encodeColor(color: Color?): Map<String, Any?> {
        if (color == null) {
            return mapOf("Type" to 1, "Values" to listOf(0.0, 0.0, 0.0, 0.0))
        }
        return when (color) {
            is Rgba -> mapOf("Type" to 1, "Values" to listOf(color.a / 255.0, color.r / 255.0, color.g / 255.0, color.b / 255.0))
            is Rgb -> mapOf("Type" to 1, "Values" to listOf(1.0, color.r / 255.0, color.g / 255.0, color.b / 255.0))
            is Cmyk -> mapOf("Type" to 2, "Values" to listOf(1.0, color.c / 255.0, color.m / 255.0, color.y / 255.0, color.k / 255.0))
            is GrayscaleColor -> mapOf("Type" to 0, "Values" to listOf(1.0, color.k / 255.0))
            else -> throw IllegalArgumentException("Invalid color type in text layer")
        }
    }

    private fun decodeStyle(obj: Map<String, Any?>, fonts: List<Font>): TextStyle {
        val style = TextStyle()
        if (obj.containsKey("Font")) {
            val idx = (obj["Font"] as? Number)?.toInt() ?: 0
            if (idx in fonts.indices) style.font = fonts[idx]
        }
        if (obj.containsKey("FontSize")) style.fontSize = (obj["FontSize"] as? Number)?.toFloat()
        if (obj.containsKey("FauxBold")) style.fauxBold = obj["FauxBold"] as? Boolean
        if (obj.containsKey("FauxItalic")) style.fauxItalic = obj["FauxItalic"] as? Boolean
        if (obj.containsKey("AutoLeading")) style.autoLeading = obj["AutoLeading"] as? Boolean
        if (obj.containsKey("Leading")) style.leading = (obj["Leading"] as? Number)?.toFloat()
        if (obj.containsKey("HorizontalScale")) style.horizontalScale = (obj["HorizontalScale"] as? Number)?.toFloat()
        if (obj.containsKey("VerticalScale")) style.verticalScale = (obj["VerticalScale"] as? Number)?.toFloat()
        if (obj.containsKey("Tracking")) style.tracking = (obj["Tracking"] as? Number)?.toFloat()
        if (obj.containsKey("AutoKerning")) style.autoKerning = obj["AutoKerning"] as? Boolean
        if (obj.containsKey("Kerning")) style.kerning = (obj["Kerning"] as? Number)?.toFloat()
        if (obj.containsKey("BaselineShift")) style.baselineShift = (obj["BaselineShift"] as? Number)?.toFloat()
        if (obj.containsKey("FontCaps")) style.fontCaps = (obj["FontCaps"] as? Number)?.toInt()
        if (obj.containsKey("FontBaseline")) style.fontBaseline = (obj["FontBaseline"] as? Number)?.toInt()
        if (obj.containsKey("Underline")) style.underline = obj["Underline"] as? Boolean
        if (obj.containsKey("Strikethrough")) style.strikethrough = obj["Strikethrough"] as? Boolean
        if (obj.containsKey("Ligatures")) style.ligatures = obj["Ligatures"] as? Boolean
        if (obj.containsKey("DLigatures")) style.dLigatures = obj["DLigatures"] as? Boolean
        if (obj.containsKey("BaselineDirection")) style.baselineDirection = (obj["BaselineDirection"] as? Number)?.toInt()
        if (obj.containsKey("Tsume")) style.tsume = (obj["Tsume"] as? Number)?.toFloat()
        if (obj.containsKey("StyleRunAlignment")) style.styleRunAlignment = (obj["StyleRunAlignment"] as? Number)?.toInt()
        if (obj.containsKey("Language")) style.language = (obj["Language"] as? Number)?.toInt()
        if (obj.containsKey("NoBreak")) style.noBreak = obj["NoBreak"] as? Boolean
        if (obj.containsKey("FillColor")) style.fillColor = decodeColor(obj["FillColor"] as Map<String, Any?>)
        if (obj.containsKey("StrokeColor")) style.strokeColor = decodeColor(obj["StrokeColor"] as Map<String, Any?>)
        if (obj.containsKey("FillFlag")) style.fillFlag = obj["FillFlag"] as? Boolean
        if (obj.containsKey("StrokeFlag")) style.strokeFlag = obj["StrokeFlag"] as? Boolean
        if (obj.containsKey("FillFirst")) style.fillFirst = obj["FillFirst"] as? Boolean
        if (obj.containsKey("YUnderline")) style.yUnderline = (obj["YUnderline"] as? Number)?.toInt()
        if (obj.containsKey("OutlineWidth")) style.outlineWidth = (obj["OutlineWidth"] as? Number)?.toFloat()
        if (obj.containsKey("CharacterDirection")) style.characterDirection = (obj["CharacterDirection"] as? Number)?.toInt()
        if (obj.containsKey("HindiNumbers")) style.hindiNumbers = obj["HindiNumbers"] as? Boolean
        if (obj.containsKey("Kashida")) style.kashida = (obj["Kashida"] as? Number)?.toFloat()
        if (obj.containsKey("DiacriticPos")) style.diacriticPos = (obj["DiacriticPos"] as? Number)?.toInt()
        return style
    }

    private fun encodeStyle(style: TextStyle, fonts: MutableList<Font>): Map<String, Any?> {
        val obj = mutableMapOf<String, Any?>()
        val font = style.font
        if (font != null) {
            var idx = fonts.indexOfFirst { it.name == font.name }
            if (idx == -1) {
                fonts.add(font)
                idx = fonts.size - 1
            }
            obj["Font"] = idx
        }
        if (style.fontSize != null) obj["FontSize"] = style.fontSize
        if (style.fauxBold != null) obj["FauxBold"] = style.fauxBold
        if (style.fauxItalic != null) obj["FauxItalic"] = style.fauxItalic
        if (style.autoLeading != null) obj["AutoLeading"] = style.autoLeading
        if (style.leading != null) obj["Leading"] = style.leading
        if (style.horizontalScale != null) obj["HorizontalScale"] = style.horizontalScale
        if (style.verticalScale != null) obj["VerticalScale"] = style.verticalScale
        if (style.tracking != null) obj["Tracking"] = style.tracking
        if (style.autoKerning != null) obj["AutoKerning"] = style.autoKerning
        if (style.kerning != null) obj["Kerning"] = style.kerning
        if (style.baselineShift != null) obj["BaselineShift"] = style.baselineShift
        if (style.fontCaps != null) obj["FontCaps"] = style.fontCaps
        if (style.fontBaseline != null) obj["FontBaseline"] = style.fontBaseline
        if (style.underline != null) obj["Underline"] = style.underline
        if (style.strikethrough != null) obj["Strikethrough"] = style.strikethrough
        if (style.ligatures != null) obj["Ligatures"] = style.ligatures
        if (style.dLigatures != null) obj["DLigatures"] = style.dLigatures
        if (style.baselineDirection != null) obj["BaselineDirection"] = style.baselineDirection
        if (style.tsume != null) obj["Tsume"] = style.tsume
        if (style.styleRunAlignment != null) obj["StyleRunAlignment"] = style.styleRunAlignment
        if (style.language != null) obj["Language"] = style.language
        if (style.noBreak != null) obj["NoBreak"] = style.noBreak
        if (style.fillColor != null) obj["FillColor"] = encodeColor(style.fillColor)
        if (style.strokeColor != null) obj["StrokeColor"] = encodeColor(style.strokeColor)
        if (style.fillFlag != null) obj["FillFlag"] = style.fillFlag
        if (style.strokeFlag != null) obj["StrokeFlag"] = style.strokeFlag
        if (style.fillFirst != null) obj["FillFirst"] = style.fillFirst
        if (style.yUnderline != null) obj["YUnderline"] = style.yUnderline
        if (style.outlineWidth != null) obj["OutlineWidth"] = style.outlineWidth
        if (style.characterDirection != null) obj["CharacterDirection"] = style.characterDirection
        if (style.hindiNumbers != null) obj["HindiNumbers"] = style.hindiNumbers
        if (style.kashida != null) obj["Kashida"] = style.kashida
        if (style.diacriticPos != null) obj["DiacriticPos"] = style.diacriticPos
        return obj
    }

    private fun decodeParagraphStyle(obj: Map<String, Any?>, fonts: List<Font>): ParagraphStyle {
        val style = ParagraphStyle()
        if (obj.containsKey("Justification")) {
            val idx = (obj["Justification"] as? Number)?.toInt() ?: 0
            if (idx in justification.indices) style.justification = justification[idx]
        }
        if (obj.containsKey("FirstLineIndent")) style.firstLineIndent = (obj["FirstLineIndent"] as? Number)?.toFloat()
        if (obj.containsKey("StartIndent")) style.startIndent = (obj["StartIndent"] as? Number)?.toFloat()
        if (obj.containsKey("EndIndent")) style.endIndent = (obj["EndIndent"] as? Number)?.toFloat()
        if (obj.containsKey("SpaceBefore")) style.spaceBefore = (obj["SpaceBefore"] as? Number)?.toFloat()
        if (obj.containsKey("SpaceAfter")) style.spaceAfter = (obj["SpaceAfter"] as? Number)?.toFloat()
        if (obj.containsKey("AutoHyphenate")) style.autoHyphenate = obj["AutoHyphenate"] as? Boolean
        if (obj.containsKey("HyphenatedWordSize")) style.hyphenatedWordSize = (obj["HyphenatedWordSize"] as? Number)?.toInt()
        if (obj.containsKey("PreHyphen")) style.preHyphen = (obj["PreHyphen"] as? Number)?.toInt()
        if (obj.containsKey("PostHyphen")) style.postHyphen = (obj["PostHyphen"] as? Number)?.toInt()
        if (obj.containsKey("ConsecutiveHyphens")) style.consecutiveHyphens = (obj["ConsecutiveHyphens"] as? Number)?.toInt()
        if (obj.containsKey("Zone")) style.zone = (obj["Zone"] as? Number)?.toFloat()
        if (obj.containsKey("WordSpacing")) {
            val lst = obj["WordSpacing"] as? List<Number>
            if (lst != null) style.wordSpacing = FloatArray(lst.size) { lst[it].toFloat() }
        }
        if (obj.containsKey("LetterSpacing")) {
            val lst = obj["LetterSpacing"] as? List<Number>
            if (lst != null) style.letterSpacing = FloatArray(lst.size) { lst[it].toFloat() }
        }
        if (obj.containsKey("GlyphSpacing")) {
            val lst = obj["GlyphSpacing"] as? List<Number>
            if (lst != null) style.glyphSpacing = FloatArray(lst.size) { lst[it].toFloat() }
        }
        if (obj.containsKey("AutoLeading")) style.autoLeading = (obj["AutoLeading"] as? Number)?.toFloat()
        if (obj.containsKey("LeadingType")) style.leadingType = (obj["LeadingType"] as? Number)?.toInt()
        if (obj.containsKey("Hanging")) style.hanging = obj["Hanging"] as? Boolean
        if (obj.containsKey("Burasagari")) style.burasagari = obj["Burasagari"] as? Boolean
        if (obj.containsKey("KinsokuOrder")) style.kinsokuOrder = (obj["KinsokuOrder"] as? Number)?.toInt()
        if (obj.containsKey("EveryLineComposer")) style.everyLineComposer = obj["EveryLineComposer"] as? Boolean
        return style
    }

    private fun encodeParagraphStyle(style: ParagraphStyle, fonts: List<Font>): Map<String, Any?> {
        val obj = mutableMapOf<String, Any?>()
        if (style.justification != null) {
            val idx = justification.indexOf(style.justification)
            obj["Justification"] = if (idx == -1) 0 else idx
        }
        if (style.firstLineIndent != null) obj["FirstLineIndent"] = style.firstLineIndent
        if (style.startIndent != null) obj["StartIndent"] = style.startIndent
        if (style.endIndent != null) obj["EndIndent"] = style.endIndent
        if (style.spaceBefore != null) obj["SpaceBefore"] = style.spaceBefore
        if (style.spaceAfter != null) obj["SpaceAfter"] = style.spaceAfter
        if (style.autoHyphenate != null) obj["AutoHyphenate"] = style.autoHyphenate
        if (style.hyphenatedWordSize != null) obj["HyphenatedWordSize"] = style.hyphenatedWordSize
        if (style.preHyphen != null) obj["PreHyphen"] = style.preHyphen
        if (style.postHyphen != null) obj["PostHyphen"] = style.postHyphen
        if (style.consecutiveHyphens != null) obj["ConsecutiveHyphens"] = style.consecutiveHyphens
        if (style.zone != null) obj["Zone"] = style.zone
        if (style.wordSpacing != null) obj["WordSpacing"] = style.wordSpacing!!.toList()
        if (style.letterSpacing != null) obj["LetterSpacing"] = style.letterSpacing!!.toList()
        if (style.glyphSpacing != null) obj["GlyphSpacing"] = style.glyphSpacing!!.toList()
        if (style.autoLeading != null) obj["AutoLeading"] = style.autoLeading
        if (style.leadingType != null) obj["LeadingType"] = style.leadingType
        if (style.hanging != null) obj["Hanging"] = style.hanging
        if (style.burasagari != null) obj["Burasagari"] = style.burasagari
        if (style.kinsokuOrder != null) obj["KinsokuOrder"] = style.kinsokuOrder
        if (style.everyLineComposer != null) obj["EveryLineComposer"] = style.everyLineComposer
        return obj
    }

    fun decodeEngineData(engineDict: Map<String, Any?>, resourceDict: Map<String, Any?>): LayerTextData {
        val fontsList = resourceDict["FontSet"] as? List<Map<String, Any?>> ?: emptyList()
        val fonts = fontsList.map { f ->
            Font(
                name = f["Name"] as? String ?: "MyriadPro-Regular",
                script = (f["Script"] as? Number)?.toInt(),
                type = (f["Type"] as? Number)?.toInt(),
                synthetic = (f["Synthetic"] as? Number)?.toInt()
            )
        }

        val editor = engineDict["Editor"] as? Map<String, Any?> ?: emptyMap()
        var text = (editor["Text"] as? String ?: "").replace("\r", "\n")
        var removedCharacters = 0
        while (text.endsWith("\n")) {
            text = text.substring(0, text.length - 1)
            removedCharacters++
        }

        val result = LayerTextData(
            text = text,
            antiAlias = antialias.getOrNull((engineDict["AntiAlias"] as? Number)?.toInt() ?: 3) ?: "smooth",
            useFractionalGlyphWidths = engineDict["UseFractionalGlyphWidths"] as? Boolean ?: true,
            superscriptSize = (resourceDict["SuperscriptSize"] as? Number)?.toFloat(),
            superscriptPosition = (resourceDict["SuperscriptPosition"] as? Number)?.toFloat(),
            subscriptSize = (resourceDict["SubscriptSize"] as? Number)?.toFloat(),
            subscriptPosition = (resourceDict["SubscriptPosition"] as? Number)?.toFloat(),
            smallCapSize = (resourceDict["SmallCapSize"] as? Number)?.toFloat()
        )

        // Paragraph styles
        val paragraphRun = engineDict["ParagraphRun"] as? Map<String, Any?>
        if (paragraphRun != null) {
            val runArray = paragraphRun["RunArray"] as? List<Map<String, Any?>> ?: emptyList()
            val runLengthArray = paragraphRun["RunLengthArray"] as? List<Number> ?: emptyList()
            val paragraphStyleRuns = mutableListOf<ParagraphStyleRun>()
            for (i in runArray.indices) {
                val run = runArray[i]
                val length = runLengthArray[i].toInt()
                val sheet = run["ParagraphSheet"] as? Map<String, Any?> ?: emptyMap()
                val properties = sheet["Properties"] as? Map<String, Any?> ?: emptyMap()
                val style = decodeParagraphStyle(properties, fonts)
                paragraphStyleRuns.add(ParagraphStyleRun(length, style))
            }

            // Remove trailing character count styles
            var counter = removedCharacters
            while (paragraphStyleRuns.isNotEmpty() && counter > 0) {
                val last = paragraphStyleRuns.last()
                if (last.length <= counter) {
                    counter -= last.length
                    paragraphStyleRuns.removeAt(paragraphStyleRuns.size - 1)
                } else {
                    paragraphStyleRuns[paragraphStyleRuns.size - 1] = ParagraphStyleRun(last.length - counter, last.style)
                    break
                }
            }
            val paragraphStyle = ParagraphStyle()
            val processedParagraphRuns = deduplicateParagraphStyle(paragraphStyle, paragraphStyleRuns)
            result.paragraphStyle = paragraphStyle
            if (processedParagraphRuns.isNotEmpty()) {
                result.paragraphStyleRuns = processedParagraphRuns
            }
        }

        // Style Runs
        val styleRun = engineDict["StyleRun"] as? Map<String, Any?>
        if (styleRun != null) {
            val runArray = styleRun["RunArray"] as? List<Map<String, Any?>> ?: emptyList()
            val runLengthArray = styleRun["RunLengthArray"] as? List<Number> ?: emptyList()
            val styleRuns = mutableListOf<TextStyleRun>()
            for (i in runArray.indices) {
                val length = runLengthArray[i].toInt()
                val sheet = runArray[i]["StyleSheet"] as? Map<String, Any?> ?: emptyMap()
                val sheetData = sheet["StyleSheetData"] as? Map<String, Any?> ?: emptyMap()
                val style = decodeStyle(sheetData, fonts)
                if (style.font == null && fonts.isNotEmpty()) {
                    style.font = fonts[0]
                }
                styleRuns.add(TextStyleRun(length, style))
            }

            var counter = removedCharacters
            while (styleRuns.isNotEmpty() && counter > 0) {
                val last = styleRuns.last()
                if (last.length <= counter) {
                    counter -= last.length
                    styleRuns.removeAt(styleRuns.size - 1)
                } else {
                    styleRuns[styleRuns.size - 1] = TextStyleRun(last.length - counter, last.style)
                    break
                }
            }
            val textStyle = TextStyle()
            val processedStyleRuns = deduplicateStyle(textStyle, styleRuns)
            result.style = textStyle
            if (processedStyleRuns.isNotEmpty()) {
                result.styleRuns = processedStyleRuns
            }
        }

        return result
    }

    fun encodeEngineData(data: LayerTextData): Map<String, Any?> {
        val text = data.text.replace(Regex("\\r?\\n"), "\r") + "\r"
        val fonts = mutableListOf<Font>(
            Font(name = "AdobeInvisFont", script = 0, type = 0, synthetic = 0)
        )

        // Find primary font
        val defFont = data.style?.font ?: data.styleRuns?.find { it.style.font != null }?.style?.font ?: defaultFont

        // Encode Style Runs
        val styleRunArray = mutableListOf<Map<String, Any?>>()
        val styleRunLengthArray = mutableListOf<Int>()
        val styleRuns = data.styleRuns ?: listOf(TextStyleRun(text.length, data.style ?: TextStyle()))

        var leftLength = text.length
        for (run in styleRuns) {
            var runLength = minOf(run.length, leftLength)
            leftLength -= runLength
            if (runLength == 0) continue

            // Extend last run to cover trailing carriage return
            if (leftLength == 1 && run == styleRuns.last()) {
                runLength++
                leftLength--
            }

            styleRunLengthArray.add(runLength)
            val mergedStyle = defaultStyle.copy(
                kerning = 0f,
                autoKerning = true,
                fillColor = Rgb(0, 0, 0)
            )

            // Manual merge
            val runStyle = run.style
            val target = mergeStyle(mergedStyle, data.style)
            val finalStyle = mergeStyle(target, runStyle)

            styleRunArray.add(
                mapOf(
                    "StyleSheet" to mapOf(
                        "StyleSheetData" to encodeStyle(finalStyle, fonts)
                    )
                )
            )
        }

        // Encode Paragraph Runs
        val paragraphRunArray = mutableListOf<Map<String, Any?>>()
        val paragraphRunLengthArray = mutableListOf<Int>()
        val paragraphRuns = data.paragraphStyleRuns

        if (paragraphRuns != null && paragraphRuns.isNotEmpty()) {
            var pLeftLength = text.length
            for (run in paragraphRuns) {
                var runLength = minOf(run.length, pLeftLength)
                pLeftLength -= runLength
                if (runLength == 0) continue

                if (pLeftLength == 1 && run == paragraphRuns.last()) {
                    runLength++
                    pLeftLength--
                }

                paragraphRunLengthArray.add(runLength)
                val finalPStyle = mergeParagraphStyle(mergeParagraphStyle(defaultParagraphStyle.copy(), data.paragraphStyle), run.style)
                paragraphRunArray.add(
                    mapOf(
                        "ParagraphSheet" to mapOf(
                            "DefaultStyleSheet" to 0,
                            "Properties" to encodeParagraphStyle(finalPStyle, fonts)
                        ),
                        "Adjustments" to mapOf("Axis" to listOf(1.0, 0.0, 1.0), "XY" to listOf(0.0, 0.0))
                    )
                )
            }
            if (pLeftLength > 0) {
                paragraphRunLengthArray.add(pLeftLength)
                val finalPStyle = mergeParagraphStyle(defaultParagraphStyle.copy(), data.paragraphStyle)
                paragraphRunArray.add(
                    mapOf(
                        "ParagraphSheet" to mapOf(
                            "DefaultStyleSheet" to 0,
                            "Properties" to encodeParagraphStyle(finalPStyle, fonts)
                        ),
                        "Adjustments" to mapOf("Axis" to listOf(1.0, 0.0, 1.0), "XY" to listOf(0.0, 0.0))
                    )
                )
            }
        } else {
            var last = 0
            for (i in 0 until text.length) {
                if (text[i].code == 13) { // '\r'
                    paragraphRunLengthArray.add(i - last + 1)
                    val finalPStyle = mergeParagraphStyle(defaultParagraphStyle.copy(), data.paragraphStyle)
                    paragraphRunArray.add(
                        mapOf(
                            "ParagraphSheet" to mapOf(
                                "DefaultStyleSheet" to 0,
                                "Properties" to encodeParagraphStyle(finalPStyle, fonts)
                            ),
                            "Adjustments" to mapOf("Axis" to listOf(1.0, 0.0, 1.0), "XY" to listOf(0.0, 0.0))
                        )
                    )
                    last = i + 1
                }
            }
        }

        val gridInfo = data.gridInfo ?: defaultGridInfo
        val writingDirection = if (data.orientation == "vertical") 2 else 0
        val procession = if (data.orientation == "vertical") 1 else 0
        val shapeType = if (data.shapeType == "box") 1 else 0

        val photoshopNode = mutableMapOf<String, Any?>("ShapeType" to shapeType)
        if (shapeType == 0) {
            photoshopNode["PointBase"] = data.pointBase?.toList() ?: listOf(0.0, 0.0)
        } else {
            photoshopNode["BoxBounds"] = data.boxBounds?.toList() ?: listOf(0.0, 0.0, 0.0, 0.0)
        }
        photoshopNode["Base"] = mapOf(
            "ShapeType" to shapeType,
            "TransformPoint0" to listOf(1.0, 0.0),
            "TransformPoint1" to listOf(0.0, 1.0),
            "TransformPoint2" to listOf(0.0, 0.0)
        )

        val defaultResources = mapOf(
            "KinsokuSet" to listOf(
                mapOf(
                    "Name" to "PhotoshopKinsokuHard",
                    "NoStart" to "、。，．・：；？！ー―’”）〕］｝〉》」』】ヽヾゝゞ々ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ゛゜?!)]},.:;℃℉¢％‰",
                    "NoEnd" to "‘“（〔［｛〈《「『【([{￥＄£＠§〒＃",
                    "Keep" to "―‥",
                    "Hanging" to "、。.,"
                ),
                mapOf(
                    "Name" to "PhotoshopKinsokuSoft",
                    "NoStart" to "、。，．・：；？！’”）〕］｝〉》」』】ヽヾゝゞ々",
                    "NoEnd" to "‘“（〔［｛〈《「『【",
                    "Keep" to "―‥",
                    "Hanging" to "、。.,"
                )
            ),
            "MojiKumiSet" to listOf(
                mapOf("InternalName" to "Photoshop6MojiKumiSet1"),
                mapOf("InternalName" to "Photoshop6MojiKumiSet2"),
                mapOf("InternalName" to "Photoshop6MojiKumiSet3"),
                mapOf("InternalName" to "Photoshop6MojiKumiSet4")
            ),
            "TheNormalStyleSheet" to 0,
            "TheNormalParagraphSheet" to 0,
            "ParagraphSheetSet" to listOf(
                mapOf(
                    "Name" to "Normal RGB",
                    "DefaultStyleSheet" to 0,
                    "Properties" to encodeParagraphStyle(mergeParagraphStyle(defaultParagraphStyle.copy(), data.paragraphStyle), fonts)
                )
            ),
            "StyleSheetSet" to listOf(
                mapOf(
                    "Name" to "Normal RGB",
                    "StyleSheetData" to encodeStyle(mergeStyle(defaultStyle.copy(font = defFont), data.style), fonts)
                )
            ),
            "FontSet" to fonts.map { f ->
                mapOf(
                    "Name" to f.name,
                    "Script" to (f.script ?: 0),
                    "Type" to (f.type ?: 0),
                    "Synthetic" to (f.synthetic ?: 0)
                )
            },
            "SuperscriptSize" to (data.superscriptSize ?: 0.583f),
            "SuperscriptPosition" to (data.superscriptPosition ?: 0.333f),
            "SubscriptSize" to (data.subscriptSize ?: 0.583f),
            "SubscriptPosition" to (data.subscriptPosition ?: 0.333f),
            "SmallCapSize" to (data.smallCapSize ?: 0.7f)
        )

        val engineDict = mapOf(
            "Editor" to mapOf("Text" to text),
            "ParagraphRun" to mapOf(
                "DefaultRunData" to mapOf(
                    "ParagraphSheet" to mapOf("DefaultStyleSheet" to 0, "Properties" to mapOf<String, Any?>()),
                    "Adjustments" to mapOf("Axis" to listOf(1.0, 0.0, 1.0), "XY" to listOf(0.0, 0.0))
                ),
                "RunArray" to paragraphRunArray,
                "RunLengthArray" to paragraphRunLengthArray,
                "IsJoinable" to 1
            ),
            "StyleRun" to mapOf(
                "DefaultRunData" to mapOf("StyleSheet" to mapOf("StyleSheetData" to mapOf<String, Any?>())),
                "RunArray" to styleRunArray,
                "RunLengthArray" to styleRunLengthArray,
                "IsJoinable" to 2
            ),
            "GridInfo" to mapOf(
                "GridIsOn" to (gridInfo.isOn ?: false),
                "ShowGrid" to (gridInfo.show ?: false),
                "GridSize" to (gridInfo.size ?: 18f),
                "GridLeading" to (gridInfo.leading ?: 22f),
                "GridColor" to encodeColor(gridInfo.color),
                "GridLeadingFillColor" to encodeColor(gridInfo.leadingFillColor),
                "AlignLineHeightToGridFlags" to (gridInfo.alignLineHeightToGridFlags ?: false)
            ),
            "AntiAlias" to maxOf(0, antialias.indexOf(data.antiAlias ?: "sharp")),
            "UseFractionalGlyphWidths" to (data.useFractionalGlyphWidths ?: true),
            "Rendered" to mapOf(
                "Version" to 1,
                "Shapes" to mapOf(
                    "WritingDirection" to writingDirection,
                    "Children" to listOf(
                        mapOf(
                            "ShapeType" to shapeType,
                            "Procession" to procession,
                            "Lines" to mapOf("WritingDirection" to writingDirection, "Children" to emptyList<Any>()),
                            "Cookie" to mapOf("Photoshop" to photoshopNode)
                        )
                    )
                )
            )
        )

        return mapOf(
            "EngineDict" to engineDict,
            "ResourceDict" to defaultResources,
            "DocumentResources" to defaultResources
        )
    }

    private fun mergeStyle(base: TextStyle, overlay: TextStyle?): TextStyle {
        if (overlay == null) return base
        val result = base.copy()
        overlay.font?.let { result.font = it }
        overlay.fontSize?.let { result.fontSize = it }
        overlay.fauxBold?.let { result.fauxBold = it }
        overlay.fauxItalic?.let { result.fauxItalic = it }
        overlay.autoLeading?.let { result.autoLeading = it }
        overlay.leading?.let { result.leading = it }
        overlay.horizontalScale?.let { result.horizontalScale = it }
        overlay.verticalScale?.let { result.verticalScale = it }
        overlay.tracking?.let { result.tracking = it }
        overlay.autoKerning?.let { result.autoKerning = it }
        overlay.kerning?.let { result.kerning = it }
        overlay.baselineShift?.let { result.baselineShift = it }
        overlay.fontCaps?.let { result.fontCaps = it }
        overlay.fontBaseline?.let { result.fontBaseline = it }
        overlay.underline?.let { result.underline = it }
        overlay.strikethrough?.let { result.strikethrough = it }
        overlay.ligatures?.let { result.ligatures = it }
        overlay.dLigatures?.let { result.dLigatures = it }
        overlay.baselineDirection?.let { result.baselineDirection = it }
        overlay.tsume?.let { result.tsume = it }
        overlay.styleRunAlignment?.let { result.styleRunAlignment = it }
        overlay.language?.let { result.language = it }
        overlay.noBreak?.let { result.noBreak = it }
        overlay.fillColor?.let { result.fillColor = it }
        overlay.strokeColor?.let { result.strokeColor = it }
        overlay.fillFlag?.let { result.fillFlag = it }
        overlay.strokeFlag?.let { result.strokeFlag = it }
        overlay.fillFirst?.let { result.fillFirst = it }
        overlay.yUnderline?.let { result.yUnderline = it }
        overlay.outlineWidth?.let { result.outlineWidth = it }
        overlay.characterDirection?.let { result.characterDirection = it }
        overlay.hindiNumbers?.let { result.hindiNumbers = it }
        overlay.kashida?.let { result.kashida = it }
        overlay.diacriticPos?.let { result.diacriticPos = it }
        return result
    }

    private fun mergeParagraphStyle(base: ParagraphStyle, overlay: ParagraphStyle?): ParagraphStyle {
        if (overlay == null) return base
        val result = base.copy()
        overlay.justification?.let { result.justification = it }
        overlay.firstLineIndent?.let { result.firstLineIndent = it }
        overlay.startIndent?.let { result.startIndent = it }
        overlay.endIndent?.let { result.endIndent = it }
        overlay.spaceBefore?.let { result.spaceBefore = it }
        overlay.spaceAfter?.let { result.spaceAfter = it }
        overlay.autoHyphenate?.let { result.autoHyphenate = it }
        overlay.hyphenatedWordSize?.let { result.hyphenatedWordSize = it }
        overlay.preHyphen?.let { result.preHyphen = it }
        overlay.postHyphen?.let { result.postHyphen = it }
        overlay.consecutiveHyphens?.let { result.consecutiveHyphens = it }
        overlay.zone?.let { result.zone = it }
        overlay.wordSpacing?.let { result.wordSpacing = it }
        overlay.letterSpacing?.let { result.letterSpacing = it }
        overlay.glyphSpacing?.let { result.glyphSpacing = it }
        overlay.autoLeading?.let { result.autoLeading = it }
        overlay.leadingType?.let { result.leadingType = it }
        overlay.hanging?.let { result.hanging = it }
        overlay.burasagari?.let { result.burasagari = it }
        overlay.kinsokuOrder?.let { result.kinsokuOrder = it }
        overlay.everyLineComposer?.let { result.everyLineComposer = it }
        return result
    }

    private fun deduplicateStyle(base: TextStyle, runs: List<TextStyleRun>): List<TextStyleRun> {
        if (runs.isEmpty()) return runs

        fun <V> isIdentical(getter: (TextStyle) -> V): Boolean {
            val firstVal = getter(runs[0].style) ?: return false
            return runs.all { getter(it.style) == firstVal }
        }

        if (isIdentical { it.font }) { base.font = runs[0].style.font; runs.forEach { it.style.font = null } }
        if (isIdentical { it.fontSize }) { base.fontSize = runs[0].style.fontSize; runs.forEach { it.style.fontSize = null } }
        if (isIdentical { it.fauxBold }) { base.fauxBold = runs[0].style.fauxBold; runs.forEach { it.style.fauxBold = null } }
        if (isIdentical { it.fauxItalic }) { base.fauxItalic = runs[0].style.fauxItalic; runs.forEach { it.style.fauxItalic = null } }
        if (isIdentical { it.autoLeading }) { base.autoLeading = runs[0].style.autoLeading; runs.forEach { it.style.autoLeading = null } }
        if (isIdentical { it.leading }) { base.leading = runs[0].style.leading; runs.forEach { it.style.leading = null } }
        if (isIdentical { it.horizontalScale }) { base.horizontalScale = runs[0].style.horizontalScale; runs.forEach { it.style.horizontalScale = null } }
        if (isIdentical { it.verticalScale }) { base.verticalScale = runs[0].style.verticalScale; runs.forEach { it.style.verticalScale = null } }
        if (isIdentical { it.tracking }) { base.tracking = runs[0].style.tracking; runs.forEach { it.style.tracking = null } }
        if (isIdentical { it.autoKerning }) { base.autoKerning = runs[0].style.autoKerning; runs.forEach { it.style.autoKerning = null } }
        if (isIdentical { it.kerning }) { base.kerning = runs[0].style.kerning; runs.forEach { it.style.kerning = null } }
        if (isIdentical { it.baselineShift }) { base.baselineShift = runs[0].style.baselineShift; runs.forEach { it.style.baselineShift = null } }
        if (isIdentical { it.fontCaps }) { base.fontCaps = runs[0].style.fontCaps; runs.forEach { it.style.fontCaps = null } }
        if (isIdentical { it.fontBaseline }) { base.fontBaseline = runs[0].style.fontBaseline; runs.forEach { it.style.fontBaseline = null } }
        if (isIdentical { it.underline }) { base.underline = runs[0].style.underline; runs.forEach { it.style.underline = null } }
        if (isIdentical { it.strikethrough }) { base.strikethrough = runs[0].style.strikethrough; runs.forEach { it.style.strikethrough = null } }
        if (isIdentical { it.ligatures }) { base.ligatures = runs[0].style.ligatures; runs.forEach { it.style.ligatures = null } }
        if (isIdentical { it.dLigatures }) { base.dLigatures = runs[0].style.dLigatures; runs.forEach { it.style.dLigatures = null } }
        if (isIdentical { it.baselineDirection }) { base.baselineDirection = runs[0].style.baselineDirection; runs.forEach { it.style.baselineDirection = null } }
        if (isIdentical { it.tsume }) { base.tsume = runs[0].style.tsume; runs.forEach { it.style.tsume = null } }
        if (isIdentical { it.styleRunAlignment }) { base.styleRunAlignment = runs[0].style.styleRunAlignment; runs.forEach { it.style.styleRunAlignment = null } }
        if (isIdentical { it.language }) { base.language = runs[0].style.language; runs.forEach { it.style.language = null } }
        if (isIdentical { it.noBreak }) { base.noBreak = runs[0].style.noBreak; runs.forEach { it.style.noBreak = null } }
        if (isIdentical { it.fillColor }) { base.fillColor = runs[0].style.fillColor; runs.forEach { it.style.fillColor = null } }
        if (isIdentical { it.strokeColor }) { base.strokeColor = runs[0].style.strokeColor; runs.forEach { it.style.strokeColor = null } }
        if (isIdentical { it.fillFlag }) { base.fillFlag = runs[0].style.fillFlag; runs.forEach { it.style.fillFlag = null } }
        if (isIdentical { it.strokeFlag }) { base.strokeFlag = runs[0].style.strokeFlag; runs.forEach { it.style.strokeFlag = null } }
        if (isIdentical { it.fillFirst }) { base.fillFirst = runs[0].style.fillFirst; runs.forEach { it.style.fillFirst = null } }
        if (isIdentical { it.yUnderline }) { base.yUnderline = runs[0].style.yUnderline; runs.forEach { it.style.yUnderline = null } }
        if (isIdentical { it.outlineWidth }) { base.outlineWidth = runs[0].style.outlineWidth; runs.forEach { it.style.outlineWidth = null } }
        if (isIdentical { it.characterDirection }) { base.characterDirection = runs[0].style.characterDirection; runs.forEach { it.style.characterDirection = null } }
        if (isIdentical { it.hindiNumbers }) { base.hindiNumbers = runs[0].style.hindiNumbers; runs.forEach { it.style.hindiNumbers = null } }
        if (isIdentical { it.kashida }) { base.kashida = runs[0].style.kashida; runs.forEach { it.style.kashida = null } }
        if (isIdentical { it.diacriticPos }) { base.diacriticPos = runs[0].style.diacriticPos; runs.forEach { it.style.diacriticPos = null } }

        fun isEmptyStyle(s: TextStyle): Boolean {
            return s.font == null && s.fontSize == null && s.fauxBold == null && s.fauxItalic == null &&
                   s.autoLeading == null && s.leading == null && s.horizontalScale == null && s.verticalScale == null &&
                   s.tracking == null && s.autoKerning == null && s.kerning == null && s.baselineShift == null &&
                   s.fontCaps == null && s.fontBaseline == null && s.underline == null && s.strikethrough == null &&
                   s.ligatures == null && s.dLigatures == null && s.baselineDirection == null && s.tsume == null &&
                   s.styleRunAlignment == null && s.language == null && s.noBreak == null && s.fillColor == null &&
                   s.strokeColor == null && s.fillFlag == null && s.strokeFlag == null && s.fillFirst == null &&
                   s.yUnderline == null && s.outlineWidth == null && s.characterDirection == null &&
                   s.hindiNumbers == null && s.kashida == null && s.diacriticPos == null
        }

        if (runs.all { isEmptyStyle(it.style) }) {
            return emptyList()
        }
        return runs
    }

    private fun deduplicateParagraphStyle(base: ParagraphStyle, runs: List<ParagraphStyleRun>): List<ParagraphStyleRun> {
        if (runs.isEmpty()) return runs

        fun <V> isIdentical(getter: (ParagraphStyle) -> V): Boolean {
            val firstVal = getter(runs[0].style) ?: return false
            return runs.all { 
                val currentVal = getter(it.style)
                if (currentVal is FloatArray && firstVal is FloatArray) {
                    currentVal.contentEquals(firstVal)
                } else {
                    currentVal == firstVal
                }
            }
        }

        if (isIdentical { it.justification }) { base.justification = runs[0].style.justification; runs.forEach { it.style.justification = null } }
        if (isIdentical { it.firstLineIndent }) { base.firstLineIndent = runs[0].style.firstLineIndent; runs.forEach { it.style.firstLineIndent = null } }
        if (isIdentical { it.startIndent }) { base.startIndent = runs[0].style.startIndent; runs.forEach { it.style.startIndent = null } }
        if (isIdentical { it.endIndent }) { base.endIndent = runs[0].style.endIndent; runs.forEach { it.style.endIndent = null } }
        if (isIdentical { it.spaceBefore }) { base.spaceBefore = runs[0].style.spaceBefore; runs.forEach { it.style.spaceBefore = null } }
        if (isIdentical { it.spaceAfter }) { base.spaceAfter = runs[0].style.spaceAfter; runs.forEach { it.style.spaceAfter = null } }
        if (isIdentical { it.autoHyphenate }) { base.autoHyphenate = runs[0].style.autoHyphenate; runs.forEach { it.style.autoHyphenate = null } }
        if (isIdentical { it.hyphenatedWordSize }) { base.hyphenatedWordSize = runs[0].style.hyphenatedWordSize; runs.forEach { it.style.hyphenatedWordSize = null } }
        if (isIdentical { it.preHyphen }) { base.preHyphen = runs[0].style.preHyphen; runs.forEach { it.style.preHyphen = null } }
        if (isIdentical { it.postHyphen }) { base.postHyphen = runs[0].style.postHyphen; runs.forEach { it.style.postHyphen = null } }
        if (isIdentical { it.consecutiveHyphens }) { base.consecutiveHyphens = runs[0].style.consecutiveHyphens; runs.forEach { it.style.consecutiveHyphens = null } }
        if (isIdentical { it.zone }) { base.zone = runs[0].style.zone; runs.forEach { it.style.zone = null } }
        if (isIdentical { it.wordSpacing }) { base.wordSpacing = runs[0].style.wordSpacing; runs.forEach { it.style.wordSpacing = null } }
        if (isIdentical { it.letterSpacing }) { base.letterSpacing = runs[0].style.letterSpacing; runs.forEach { it.style.letterSpacing = null } }
        if (isIdentical { it.glyphSpacing }) { base.glyphSpacing = runs[0].style.glyphSpacing; runs.forEach { it.style.glyphSpacing = null } }
        if (isIdentical { it.autoLeading }) { base.autoLeading = runs[0].style.autoLeading; runs.forEach { it.style.autoLeading = null } }
        if (isIdentical { it.leadingType }) { base.leadingType = runs[0].style.leadingType; runs.forEach { it.style.leadingType = null } }
        if (isIdentical { it.hanging }) { base.hanging = runs[0].style.hanging; runs.forEach { it.style.hanging = null } }
        if (isIdentical { it.burasagari }) { base.burasagari = runs[0].style.burasagari; runs.forEach { it.style.burasagari = null } }
        if (isIdentical { it.kinsokuOrder }) { base.kinsokuOrder = runs[0].style.kinsokuOrder; runs.forEach { it.style.kinsokuOrder = null } }
        if (isIdentical { it.everyLineComposer }) { base.everyLineComposer = runs[0].style.everyLineComposer; runs.forEach { it.style.everyLineComposer = null } }

        fun isEmptyParagraphStyle(s: ParagraphStyle): Boolean {
            return s.justification == null && s.firstLineIndent == null && s.startIndent == null && s.endIndent == null &&
                   s.spaceBefore == null && s.spaceAfter == null && s.autoHyphenate == null && s.hyphenatedWordSize == null &&
                   s.preHyphen == null && s.postHyphen == null && s.consecutiveHyphens == null && s.zone == null &&
                   s.wordSpacing == null && s.letterSpacing == null && s.glyphSpacing == null && s.autoLeading == null &&
                   s.leadingType == null && s.hanging == null && s.burasagari == null && s.kinsokuOrder == null &&
                   s.everyLineComposer == null
        }

        if (runs.all { isEmptyParagraphStyle(it.style) }) {
            return emptyList()
        }
        return runs
    }
}
