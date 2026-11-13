package com.wip.psdbuilder

import com.wip.kpsd.AutoFit
import com.wip.kpsd.EffectiveTextLayout
import com.wip.kpsd.Font
import com.wip.kpsd.PsdBounds
import com.wip.kpsd.TextBoundary
import com.wip.kpsd.TextFormatter
import com.wip.kpsd.TextStyle
import com.wip.kpsd.VerticalAlignment
import com.wip.kpsd.WordBreak
import java.awt.Graphics2D
import java.awt.image.BufferedImage

/**
 * Word breaking and hyphenation strategy for PSD text layers.
 */
enum class PsdWordBreak {
    /** Do not break or hyphenate words across lines. */
    NONE,

    /** Break long words using hyphens when wrapping, and enable Photoshop auto-hyphenation. */
    HYPHENATE,

    /** Break long words at character boundaries without hyphens. */
    BREAK_WORD;

    /** Maps this setting enum to KPsd's [WordBreak]. */
    fun toKpsd(): WordBreak = when (this) {
        NONE -> WordBreak.NONE
        HYPHENATE -> WordBreak.HYPHENATE
        BREAK_WORD -> WordBreak.BREAK_WORD
    }
}

/**
 * Capability-level override for [PsdWordBreak], where [DEFAULT] defers to the global plugin setting.
 */
enum class WordBreakMode {
    DEFAULT,
    NONE,
    HYPHENATE,
    BREAK_WORD;

    /** Resolves the effective [PsdWordBreak] using [setting] when set to [DEFAULT]. */
    fun resolve(setting: PsdWordBreak?): PsdWordBreak = when (this) {
        DEFAULT -> setting ?: PsdWordBreak.NONE
        NONE -> PsdWordBreak.NONE
        HYPHENATE -> PsdWordBreak.HYPHENATE
        BREAK_WORD -> PsdWordBreak.BREAK_WORD
    }
}

/**
 * Tri-state toggle for capability-level boolean overrides, where [DEFAULT] defers to the plugin setting.
 */
enum class FeatureToggleMode {
    DEFAULT,
    ENABLED,
    DISABLED;

    /** Resolves the effective boolean flag using [setting] (or [fallback] if null) when set to [DEFAULT]. */
    fun resolve(setting: Boolean?, fallback: Boolean = true): Boolean = when (this) {
        DEFAULT -> setting ?: fallback
        ENABLED -> true
        DISABLED -> false
    }
}

/**
 * Computed text layer configuration produced by [TextLayoutHelper.resolveLayerTextConfig].
 */
data class ResolvedTextLayerConfig(
    val textValue: String,
    val fontSize: Float,
    val boxBounds: FloatArray,
    val textTop: Float,
    val textBottom: Float,
    val boundaryShape: TextBoundary?,
    val autoHyphenate: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedTextLayerConfig) return false
        if (textValue != other.textValue) return false
        if (fontSize != other.fontSize) return false
        if (!boxBounds.contentEquals(other.boxBounds)) return false
        if (textTop != other.textTop) return false
        if (textBottom != other.textBottom) return false
        if (boundaryShape != other.boundaryShape) return false
        if (autoHyphenate != other.autoHyphenate) return false
        return true
    }

    override fun hashCode(): Int {
        var result = textValue.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + boxBounds.contentHashCode()
        result = 31 * result + textTop.hashCode()
        result = 31 * result + textBottom.hashCode()
        result = 31 * result + (boundaryShape?.hashCode() ?: 0)
        result = 31 * result + autoHyphenate.hashCode()
        return result
    }
}

/**
 * Resolves text fitting, line-wrapping, hardcoded newline suppression, and hyphenation settings
 * for KPsd text layers.
 */
object TextLayoutHelper {
    private const val DEFAULT_MIN_FONT_SIZE = 8f
    private const val AUTO_LEADING_MULTIPLIER = 1.2f

    private val graphics: Graphics2D by lazy {
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
    }

    private data class SingleLineFit(val fontSize: Float, val visualHeight: Float)

    /**
     * Computes the effective text string, font size, box bounds, and EngineData hyphenation flag.
     *
     * When [allowLineWrapping] is false, collapses newlines so text remains on one line, auto-fits
     * the font size to fit horizontally and vertically, and centers the box vertically.
     * When [allowLineWrapping] is true and [insertHardcodedNewlines] is false, uses KPsd's
     * [TextFormatter] only to determine optimal font size and vertical centering, omitting
     * [TextBoundary] on the layer builder so KPsd does not rewrite [text] with hardcoded `\r` breaks.
     */
    fun resolveLayerTextConfig(
        text: String,
        layout: EffectiveTextLayout,
        fontName: String,
        initialFontSize: Int,
        minFontSize: Float = DEFAULT_MIN_FONT_SIZE,
        wordBreak: WordBreak,
        allowLineWrapping: Boolean,
        insertHardcodedNewlines: Boolean
    ): ResolvedTextLayerConfig {
        val boxW = layout.boxWidth.toFloat()
        val boxH = layout.boxHeight.toFloat()
        val bounds = PsdBounds(0f, 0f, boxW, boxH)
        val maxFontSize = initialFontSize.toFloat().coerceAtLeast(1f)
        val effectiveMinSize = minOf(minFontSize, maxFontSize)
        val autoHyphenate = (wordBreak == WordBreak.HYPHENATE)

        if (!allowLineWrapping) {
            val singleLineText = normalizeToSingleLine(text)
            val fit = fitSingleLine(
                text = singleLineText,
                fontName = fontName,
                minFontSize = effectiveMinSize,
                maxFontSize = maxFontSize,
                bounds = bounds,
                shape = layout.boundaryShape
            )
            val shift = ((boxH - fit.visualHeight) / 2f).coerceAtLeast(0f)
            return ResolvedTextLayerConfig(
                textValue = singleLineText,
                fontSize = fit.fontSize,
                boxBounds = floatArrayOf(0f, shift, boxW, boxH - shift),
                textTop = shift,
                textBottom = boxH - shift,
                boundaryShape = null,
                autoHyphenate = false
            )
        }

        if (insertHardcodedNewlines) {
            return ResolvedTextLayerConfig(
                textValue = text,
                fontSize = maxFontSize,
                boxBounds = floatArrayOf(0f, 0f, boxW, boxH),
                textTop = 0f,
                textBottom = boxH,
                boundaryShape = layout.boundaryShape,
                autoHyphenate = autoHyphenate
            )
        }

        val autoFit = AutoFit(minSize = effectiveMinSize, maxSize = maxFontSize)
        val baseStyle = TextStyle(
            font = Font(fontName),
            fontSize = maxFontSize,
            autoFit = autoFit
        )
        val fittedSize = TextFormatter.resolveFontSize(
            text = text,
            baseStyle = baseStyle,
            bounds = bounds,
            shape = layout.boundaryShape,
            autoFit = autoFit,
            wordBreak = wordBreak,
            alignment = VerticalAlignment.CENTER
        )
        val formatResult = TextFormatter.formatTextInternal(
            text = text,
            style = baseStyle.copy(fontSize = fittedSize),
            bounds = bounds,
            shape = layout.boundaryShape,
            wordBreak = wordBreak,
            alignment = VerticalAlignment.CENTER
        )
        val shift = ((boxH - formatResult.visualHeight) / 2f).coerceAtLeast(0f)
        return ResolvedTextLayerConfig(
            textValue = text,
            fontSize = fittedSize,
            boxBounds = floatArrayOf(0f, shift, boxW, boxH - shift),
            textTop = shift,
            textBottom = boxH - shift,
            boundaryShape = null,
            autoHyphenate = autoHyphenate
        )
    }

    /**
     * Collapses explicit line breaks into single spaces so single-line text never wraps on embedded newlines.
     */
    internal fun normalizeToSingleLine(text: String): String {
        val normalized = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
        return normalized.ifEmpty { text }
    }

    private fun fitSingleLine(
        text: String,
        fontName: String,
        minFontSize: Float,
        maxFontSize: Float,
        bounds: PsdBounds,
        shape: TextBoundary
    ): SingleLineFit {
        var currentSize = maxFontSize
        while (currentSize >= minFontSize) {
            val awtFont = TextFormatter.getAwtFont(fontName, currentSize, isBold = false, isItalic = false)
            val metrics = graphics.getFontMetrics(awtFont)
            val leading = currentSize * AUTO_LEADING_MULTIPLIER
            val visualHeight = (metrics.ascent + metrics.descent).toFloat()

            // Center line vertically around y = 0 so available width is sampled at actual glyph top/bottom
            val startBaseline = -(metrics.descent - metrics.ascent) / 2f
            val topY = startBaseline - metrics.ascent
            val bottomY = startBaseline + metrics.descent
            val availW = minOf(
                shape.getAvailableWidth(topY, bounds),
                shape.getAvailableWidth(bottomY, bounds)
            )
            val lineWidth = metrics.stringWidth(text).toFloat() * TextFormatter.AWT_WIDTH_MULTIPLIER
            val fitsHorizontally = lineWidth <= availW
            val fitsVertically = leading <= bounds.height &&
                shape.getAvailableWidth(-(leading / 2f), bounds) > 0f &&
                shape.getAvailableWidth(leading / 2f, bounds) > 0f

            if (fitsHorizontally && fitsVertically) {
                return SingleLineFit(fontSize = currentSize, visualHeight = visualHeight)
            }
            currentSize -= 1f
        }

        val minAwtFont = TextFormatter.getAwtFont(fontName, minFontSize, isBold = false, isItalic = false)
        val minMetrics = graphics.getFontMetrics(minAwtFont)
        return SingleLineFit(
            fontSize = minFontSize,
            visualHeight = (minMetrics.ascent + minMetrics.descent).toFloat()
        )
    }
}
