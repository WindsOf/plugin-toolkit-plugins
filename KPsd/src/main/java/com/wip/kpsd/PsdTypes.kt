package com.wip.kpsd

import java.awt.image.BufferedImage

enum class ColorMode(val value: Int) {
    Bitmap(0),
    Grayscale(1),
    Indexed(2),
    RGB(3),
    CMYK(4),
    Multichannel(7),
    Duotone(8),
    Lab(9);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: RGB
    }
}

enum class SectionDividerType(val value: Int) {
    Other(0),
    OpenFolder(1),
    ClosedFolder(2),
    BoundingSectionDivider(3);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: Other
    }
}

enum class Compression(val value: Int) {
    RawData(0),
    RleCompressed(1),
    ZipWithoutPrediction(2),
    ZipWithPrediction(3);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: RawData
    }
}

enum class ChannelID(val value: Int) {
    Transparency(-1),
    Color0(0),
    Color1(1),
    Color2(2),
    Color3(3),
    Color4(4),
    UserMask(-2),
    RealUserMask(-3);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: Color0
    }
}

sealed interface Color

data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int) : Color
data class Rgb(val r: Int, val g: Int, val b: Int) : Color
data class Frgb(val fr: Float, val fg: Float, val fb: Float) : Color
data class Hsb(val h: Float, val s: Float, val b: Float) : Color
data class Cmyk(val c: Int, val m: Int, val y: Int, val k: Int) : Color
data class Lab(val l: Float, val a: Float, val b: Float) : Color
data class GrayscaleColor(val k: Int) : Color

data class PixelData(
    val width: Int,
    val height: Int,
    val data: ByteArray // 8-bit RGBA (or channel bytes depending on depth)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelData) return false
        if (width != other.width) return false
        if (height != other.height) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class LayerMaskData(
    var top: Int? = null,
    var left: Int? = null,
    var bottom: Int? = null,
    var right: Int? = null,
    var defaultColor: Int? = null,
    var disabled: Boolean? = null,
    var positionRelativeToLayer: Boolean? = null,
    var fromVectorData: Boolean? = null,
    var userMaskDensity: Float? = null,
    var userMaskFeather: Double? = null,
    var vectorMaskDensity: Float? = null,
    var vectorMaskFeather: Double? = null,
    var canvas: BufferedImage? = null,
    var imageData: PixelData? = null
)

data class SectionDivider(
    var type: SectionDividerType,
    var key: String? = null,
    var subType: Int? = null
)

data class Font(
    val name: String,
    val script: Int? = null,
    val type: Int? = null,
    val synthetic: Int? = null
)

data class Warp(
    var style: String? = null,
    var value: Float? = null,
    var values: FloatArray? = null,
    var perspective: Float? = null,
    var perspectiveOther: Float? = null,
    var rotate: String? = null,
    var bounds: UnitsBounds? = null,
    var uOrder: Int? = null,
    var vOrder: Int? = null,
    var deformNumRows: Int? = null,
    var deformNumCols: Int? = null,
    var customEnvelopeWarp: CustomEnvelopeWarp? = null
)

data class UnitsBounds(
    val top: UnitsValue,
    val left: UnitsValue,
    val right: UnitsValue,
    val bottom: UnitsValue
)

data class UnitsValue(
    val units: String, // 'Pixels', 'Points', etc.
    val value: Float
)

data class CustomEnvelopeWarp(
    val quiltSliceX: FloatArray? = null,
    val quiltSliceY: FloatArray? = null,
    val meshPoints: List<Point>
)

data class Point(val x: Float, val y: Float)

data class TextStyle(
    var font: Font? = null,
    var fontSize: Float? = null,
    var fauxBold: Boolean? = null,
    var fauxItalic: Boolean? = null,
    var autoLeading: Boolean? = null,
    var leading: Float? = null,
    var horizontalScale: Float? = null,
    var verticalScale: Float? = null,
    var tracking: Float? = null,
    var autoKerning: Boolean? = null,
    var kerning: Float? = null,
    var baselineShift: Float? = null,
    var fontCaps: Int? = null,
    var fontBaseline: Int? = null,
    var underline: Boolean? = null,
    var strikethrough: Boolean? = null,
    var ligatures: Boolean? = null,
    var dLigatures: Boolean? = null,
    var baselineDirection: Int? = null,
    var tsume: Float? = null,
    var styleRunAlignment: Int? = null,
    var language: Int? = null,
    var noBreak: Boolean? = null,
    var fillColor: Color? = null,
    var strokeColor: Color? = null,
    var fillFlag: Boolean? = null,
    var strokeFlag: Boolean? = null,
    var fillFirst: Boolean? = null,
    var yUnderline: Int? = null,
    var outlineWidth: Float? = null,
    var characterDirection: Int? = null,
    var hindiNumbers: Boolean? = null,
    var kashida: Float? = null,
    var diacriticPos: Int? = null
)

data class TextStyleRun(
    val length: Int,
    val style: TextStyle
)

data class ParagraphStyle(
    var justification: String? = null,
    var firstLineIndent: Float? = null,
    var startIndent: Float? = null,
    var endIndent: Float? = null,
    var spaceBefore: Float? = null,
    var spaceAfter: Float? = null,
    var autoHyphenate: Boolean? = null,
    var hyphenatedWordSize: Int? = null,
    var preHyphen: Int? = null,
    var postHyphen: Int? = null,
    var consecutiveHyphens: Int? = null,
    var zone: Float? = null,
    var wordSpacing: FloatArray? = null,
    var letterSpacing: FloatArray? = null,
    var glyphSpacing: FloatArray? = null,
    var autoLeading: Float? = null,
    var leadingType: Int? = null,
    var hanging: Boolean? = null,
    var burasagari: Boolean? = null,
    var kinsokuOrder: Int? = null,
    var everyLineComposer: Boolean? = null
)

data class ParagraphStyleRun(
    val length: Int,
    val style: ParagraphStyle
)

data class TextGridInfo(
    var isOn: Boolean? = null,
    var show: Boolean? = null,
    var size: Float? = null,
    var leading: Float? = null,
    var color: Color? = null,
    var leadingFillColor: Color? = null,
    var alignLineHeightToGridFlags: Boolean? = null
)

data class LayerTextData(
    var text: String,
    var transform: DoubleArray? = null,
    var antiAlias: String? = null,
    var gridding: String? = null,
    var orientation: String? = null,
    var index: Int? = null,
    var warp: Warp? = null,
    var top: Float? = null,
    var left: Float? = null,
    var bottom: Float? = null,
    var right: Float? = null,
    var gridInfo: TextGridInfo? = null,
    var useFractionalGlyphWidths: Boolean? = null,
    var style: TextStyle? = null,
    var styleRuns: List<TextStyleRun>? = null,
    var paragraphStyle: ParagraphStyle? = null,
    var paragraphStyleRuns: List<ParagraphStyleRun>? = null,
    var superscriptSize: Float? = null,
    var superscriptPosition: Float? = null,
    var subscriptSize: Float? = null,
    var subscriptPosition: Float? = null,
    var smallCapSize: Float? = null,
    var shapeType: String? = null, // 'point' or 'box'
    var pointBase: FloatArray? = null,
    var boxBounds: FloatArray? = null,
    var bounds: UnitsBounds? = null,
    var boundingBox: UnitsBounds? = null
)

data class GlobalLayerMaskInfo(
    val overlayColorSpace: Int,
    val colorSpace1: Int,
    val colorSpace2: Int,
    val colorSpace3: Int,
    val colorSpace4: Int,
    val opacity: Float,
    val kind: Int
)

data class ImageResources(
    var layersGroup: IntArray? = null,
    var layerGroupsEnabledId: IntArray? = null
    // Add other fields as needed
)

data class BlendingRanges(
    val compositeGrayBlendSource: ByteArray,
    val compositeGraphBlendDestinationRange: ByteArray,
    val ranges: List<BlendingRange>
)

data class BlendingRange(
    val sourceRange: ByteArray,
    val destRange: ByteArray
)

data class Layer(
    var name: String? = null,
    var top: Int = 0,
    var left: Int = 0,
    var bottom: Int = 0,
    var right: Int = 0,
    var blendMode: String? = "normal",
    var opacity: Float = 1f,
    var clipping: Boolean = false,
    var hidden: Boolean = false,
    var transparencyProtected: Boolean = false,
    var effectsOpen: Boolean = false,
    var sectionDivider: SectionDivider? = null,
    var opened: Boolean = true,
    var children: MutableList<Layer>? = null,
    var imageData: PixelData? = null,
    var canvas: BufferedImage? = null,
    var mask: LayerMaskData? = null,
    var realMask: LayerMaskData? = null,
    var text: LayerTextData? = null,
    var id: Int? = null,
    var nameSource: String? = null,
    var linkGroup: Int? = null,
    var linkGroupEnabled: Boolean? = null,
    var blendingRanges: BlendingRanges? = null
)

data class Psd(
    var width: Int = 0,
    var height: Int = 0,
    var channels: Int = 4,
    var bitsPerChannel: Int = 8,
    var colorMode: ColorMode = ColorMode.RGB,
    var palette: MutableList<Rgb>? = null,
    var children: MutableList<Layer> = mutableListOf(),
    var imageData: PixelData? = null,
    var canvas: BufferedImage? = null,
    var globalLayerMaskInfo: GlobalLayerMaskInfo? = null,
    var imageResources: ImageResources? = null
)
