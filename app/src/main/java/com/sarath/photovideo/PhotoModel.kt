package com.sarath.photovideo

import android.graphics.*
import androidx.compose.ui.graphics.Color as ComposeColor

enum class PhotoFilter(val label: String) {
    NONE("Original"), MONO("Mono"), NOIR("Noir"), SEPIA("Sepia"), VIVID("Vivid"), COOL("Cool"), WARM("Warm"),
    FADE("Fade"), DRAMATIC("Dramatic"), SUNSET("Sunset"), MINT("Mint"), RETRO("Retro"),
}
enum class CropRatio(val label: String, val ratio: Float?) {
    FREE("Free", null), SQUARE("1:1", 1f), R45("4:5", 0.8f), R169("16:9", 16f / 9f), R916("9:16", 9f / 16f), R43("4:3", 4f / 3f)
}
enum class Palette(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()), BLACK("Black", 0xFF000000.toInt()), YELLOW("Yellow", 0xFFFFD43B.toInt()),
    CORAL("Coral", 0xFFFF6B8A.toInt()), TEAL("Teal", 0xFF2DD4BF.toInt()), VIOLET("Violet", 0xFF7C5CFF.toInt());
    val compose get() = ComposeColor(argb)
}
enum class Background(val label: String) { KEEP("Original"), BLUR("Blur"), COLOR("Colour"), REMOVE("Remove") }

/** Crop rectangle, normalised (0..1) within the rotated/flipped image. */
data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val isFull get() = l <= 0f && t <= 0f && r >= 1f && b >= 1f
    companion object {
        /** Largest centred rect with aspect [ratio] inside an image of aspect [imageAspect] (both width/height). */
        fun forRatio(ratio: Float?, imageAspect: Float): CropRect {
            if (ratio == null) return CropRect()
            val (nw, nh) = if (ratio > imageAspect) 1f to imageAspect / ratio else ratio / imageAspect to 1f
            return CropRect((1f - nw) / 2, (1f - nh) / 2, (1f + nw) / 2, (1f + nh) / 2)
        }
    }
}

/** Points are normalised (0..1) in the cropped image so they survive preview/full-size rendering. */
data class Stroke(val points: List<Pair<Float, Float>>, val color: Palette, val width: Float)
data class Sticker(val emoji: String, val x: Float = 0.5f, val y: Float = 0.5f, val size: Float = 0.2f, val rotation: Float = 0f)

enum class TextFont(val label: String, val family: String) {
    SANS("Sans", "sans-serif"), SERIF("Serif", "serif"), MONO("Mono", "monospace"), SCRIPT("Script", "cursive"), NARROW("Narrow", "sans-serif-condensed"),
}
/** A text layer. x,y are the centre in 0..1 of the final image; size is a fraction of the image height. */
data class TextLayer(
    val text: String = "Your text", val x: Float = 0.5f, val y: Float = 0.5f, val size: Float = 0.08f, val color: Palette = Palette.WHITE,
    val font: TextFont = TextFont.SANS, val bold: Boolean = true, val italic: Boolean = false, val box: Boolean = false, val rotation: Float = 0f,
)
/** A user picture (logo, cut-out, photo) placed on top. size is a fraction of the image width. */
data class ImageLayer(val uri: String, val x: Float = 0.5f, val y: Float = 0.5f, val size: Float = 0.3f, val rotation: Float = 0f, val alpha: Float = 1f)

/** Immutable, serialisable-in-spirit edit description: non-destructive and cheap to push on the undo stack. */
data class PhotoEdit(
    val rotation: Int = 0, val flipH: Boolean = false, val crop: CropRatio = CropRatio.FREE, val cropRect: CropRect = CropRect(),
    val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f, val warmth: Float = 0f,
    val vignette: Float = 0f, val sharpen: Float = 0f, val fade: Float = 0f, val filter: PhotoFilter = PhotoFilter.NONE,
    val texts: List<TextLayer> = emptyList(), val images: List<ImageLayer> = emptyList(),
    val stickers: List<Sticker> = emptyList(), val strokes: List<Stroke> = emptyList(),
    val border: Float = 0f, val borderColor: Palette = Palette.WHITE,
    val background: Background = Background.KEEP, val backgroundColor: Palette = Palette.WHITE,
)

class UndoStack<T>(initial: T) {
    var current: T = initial; private set
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun push(v: T) { if (v == current) return; undo.addLast(current); redo.clear(); current = v }
    fun undo() { if (undo.isNotEmpty()) { redo.addLast(current); current = undo.removeLast() } }
    fun redo() { if (redo.isNotEmpty()) { undo.addLast(current); current = redo.removeLast() } }
    /** Update without history (during a drag) ... */
    fun live(v: T) { current = v }
    /** ... then record the state before the drag as one undo step. */
    fun commitFrom(base: T) { if (base != current) { undo.addLast(base); redo.clear() } }
}
