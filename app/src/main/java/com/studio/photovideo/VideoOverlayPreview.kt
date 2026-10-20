package com.studio.photovideo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Which overlay is being edited: kind 0 = caption, 1 = sticker/picture. */
data class OverlaySel(val kind: Int, val index: Int)

/**
 * Draws the captions, stickers and pictures on top of the preview, in the same canvas frame as the export, so what you
 * place here is where it lands in the file. Each element is draggable; the selected one is shown even outside its window.
 * Timing uses the clip positions without speed changes (the preview plays clips at normal speed).
 */
@Composable
fun OverlayLayer(p: VideoProject, posMs: Long, sel: OverlaySel?, editing: Boolean, onSelect: (OverlaySel) -> Unit, onMove: (OverlaySel, Float, Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val primary = MaterialTheme.colorScheme.primary
        fun Modifier.layer(s: OverlaySel, active: Boolean): Modifier {
            val isSel = sel == s && editing
            return this.alpha(if (active) 1f else 0.45f)
                .then(if (isSel) Modifier.border(BorderStroke(2.dp, primary), RoundedCornerShape(8.dp)) else Modifier)
                .then(if (editing) Modifier.pointerInput(s, w, h) {
                    detectDragGestures(onDragStart = { onSelect(s) }) { c, d -> c.consume(); onMove(s, d.x / (w / 2f), -d.y / (h / 2f)) }
                }.clickable { onSelect(s) } else Modifier)
        }
        p.captions.forEachIndexed { i, c ->
            val s = OverlaySel(0, i)
            val active = posMs >= c.startMs && posMs < c.endMs
            if (active || (editing && sel == s) && c.text.isNotBlank()) {
                val fontPx = w / 13f * p.captionSize
                val (fg, bg) = when (p.captionStyle) {
                    CaptionStyle.BOXED -> Color.White to Color(0xCC000000)
                    CaptionStyle.HIGHLIGHT -> Color.Black to Color(0xFFFFD43B)
                    CaptionStyle.NEON -> Color(0xFF2DD4BF) to Color.Transparent
                    CaptionStyle.BOLD -> Color(0xFFFFD43B) to Color.Transparent
                    CaptionStyle.CLASSIC -> Color.White to Color.Transparent
                }
                Text(
                    c.text, Modifier.align(BiasAlignment(c.x, -c.y)).widthIn(max = with(density) { (w * 0.88f).toDp() }).layer(s, active)
                        .background(bg, RoundedCornerShape(with(density) { (fontPx / 2).toDp() })).padding(with(density) { (fontPx * 0.45f).toDp() }),
                    color = fg, fontSize = with(density) { fontPx.toSp() }, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(shadow = if (bg == Color.Transparent) Shadow(Color.Black, blurRadius = 8f) else null),
                )
            }
        }
        p.stickers.forEachIndexed { i, st ->
            val s = OverlaySel(1, i)
            val active = posMs >= st.startMs && posMs < st.endMs
            if (active || (editing && sel == s)) {
                val sizeDp = with(density) { (w * st.size).toDp() }
                val m = Modifier.align(BiasAlignment(st.x, -st.y)).layer(s, active)
                if (st.imageUri != null) AsyncImage(st.imageUri, "Picture", m.width(sizeDp).graphicsRotation(st.rotation), contentScale = ContentScale.FillWidth)
                else Text(st.emoji, m.graphicsRotation(st.rotation), fontSize = with(density) { (w * st.size * 0.85f).toSp() })
            }
        }
    }
}

private fun Modifier.graphicsRotation(deg: Float): Modifier = this.rotate(deg)
