package com.video.framora

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The in-app "Reduce motion" switch (Settings). Observable, so every animated element reacts the moment it is flipped. */
object MotionPrefs {
    var reduce by mutableStateOf(false)
    private const val KEY = "reduce_motion"
    fun load(prefs: android.content.SharedPreferences) { reduce = prefs.getBoolean(KEY, false) }
    fun set(prefs: android.content.SharedPreferences, v: Boolean) { reduce = v; prefs.edit().putBoolean(KEY, v).apply() }
}

/** False when the user switched system animations off ("Remove animations") or turned on Reduce motion in the app, so decorative motion stays still. */
@Composable
fun rememberMotionOn(): Boolean {
    val ctx = LocalContext.current
    val system = remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true) }
    return system && !MotionPrefs.reduce
}

/** Clickable that squashes slightly while pressed and springs back: makes every card feel physical. */
fun Modifier.bounceClick(role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, LocalIndication.current, role = role, onClick = onClick)
}

/** Slow vertical bob, e.g. for a hero icon. */
fun Modifier.floating(amplitude: androidx.compose.ui.unit.Dp = 6.dp, periodMs: Int = 2600, phase: Float = 0f): Modifier = composed {
    if (!rememberMotionOn()) return@composed this
    val t = rememberInfiniteTransition(label = "float")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "floatV")
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { amplitude.toPx() }
    this.graphicsLayer { translationY = sin((v + phase) * 2 * PI).toFloat() * px }
}

/** A soft diagonal light sweep that passes over the content every few seconds. */
fun Modifier.sheen(periodMs: Int = 4200, strength: Float = 0.22f): Modifier = composed {
    if (!rememberMotionOn()) return@composed this
    val t = rememberInfiniteTransition(label = "sheen")
    val p by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(keyframes { durationMillis = periodMs; -0.4f at 0; 1.4f at (periodMs * 0.45f).toInt() using FastOutSlowInEasing; 1.4f at periodMs }), label = "sheenP")
    this.drawWithContent {
        drawContent()
        val w = size.width; val x = p * w
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent), Offset(x - w * 0.25f, 0f), Offset(x + w * 0.05f, size.height * 0.6f)), blendMode = BlendMode.SrcOver)
    }
}

/** Fades and lifts the content in, [index] * [stepMs] after it first appears. */
fun Modifier.entrance(index: Int, stepMs: Int = 60): Modifier = composed {
    val on = rememberMotionOn()
    val a = remember { Animatable(if (on) 0f else 1f) }
    LaunchedEffect(Unit) { if (on) { delay((index * stepMs).toLong()); a.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) } }
    this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 36.dp.toPx() }
}

/** Three drifting colour glows behind the screen; the signature "premium" backdrop. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, colors: List<Color> = ThemePrefs.active.aurora, intensity: Float = 1f, content: @Composable BoxScope.() -> Unit = {}) {
    val on = rememberMotionOn()
    // Only run the transition while motion is on, so a still screen costs no frames. The value is read in the draw block only,
    // so the content above is not recomposed every frame.
    val a: State<Float> = if (on) rememberInfiniteTransition(label = "aurora").animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "a") else remember { mutableFloatStateOf(0.15f) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val alpha = (if (dark) 0.42f else 0.22f) * intensity
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val phase = a.value
            fun blob(i: Int, cx: Float, cy: Float, r: Float) {
                val ang = (phase + i / 3f) * 2 * PI
                val c = Offset(size.width * (cx + 0.22f * cos(ang).toFloat()), size.height * (cy + 0.12f * sin(ang * 1.3).toFloat()))
                drawCircle(Brush.radialGradient(listOf(colors[i % colors.size].copy(alpha = alpha), Color.Transparent), c, r), r, c)
            }
            blob(0, 0.15f, 0.12f, size.width * 0.95f)
            blob(1, 0.95f, 0.38f, size.width * 0.85f)
            blob(2, 0.3f, 0.85f, size.width * 1.0f)
        }
        content()
    }
}

/** Twinkling sparkle dots over a region (used behind hero art and on the onboarding pages). */
@Composable
fun Sparkles(modifier: Modifier = Modifier, count: Int = 18, color: Color = Color.White) {
    val on = rememberMotionOn()
    val v by (if (on) rememberInfiniteTransition(label = "sparkles").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sv") else remember { mutableFloatStateOf(0f) })
    val seeds = remember { List(count) { Triple(Random(it * 31 + 7).nextFloat(), Random(it * 57 + 3).nextFloat(), Random(it * 13 + 1).nextFloat()) } }
    Canvas(modifier) {
        seeds.forEach { (x, y, ph) ->
            val tw = if (on) 0.5f + 0.5f * sin((v + ph) * 2 * PI).toFloat() else 0.6f
            val r = (1.2f + 2.6f * ph) * tw * density
            drawCircle(color.copy(alpha = 0.15f + 0.65f * tw), r, Offset(x * size.width, y * size.height))
        }
    }
}

/** Confetti that bursts once from the top centre when [play] becomes true. */
@Composable
fun Confetti(play: Boolean, modifier: Modifier = Modifier, seed: Int = 1) {
    val on = rememberMotionOn()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(play) { if (play && on) { progress.snapTo(0f); progress.animateTo(1f, tween(2600, easing = LinearEasing)) } }
    val palette = listOf(Studio.Violet, Studio.Coral, Studio.Amber, Studio.Teal, Studio.Sky, Color.White)
    val pieces = remember(seed) { List(70) { i -> val r = Random(seed * 1000 + i); Piece(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat(), palette[r.nextInt(palette.size)], r.nextBoolean()) } }
    if (!play || !on || progress.value >= 1f) return
    Canvas(modifier) {
        val t = progress.value
        pieces.forEach { p ->
            val angle = (-PI / 2) + (p.a - 0.5f) * PI * 1.35
            val speed = (0.45f + p.b * 0.85f) * size.height * 0.85f
            val x = size.width / 2 + cos(angle).toFloat() * speed * t * 0.7f
            val y = size.height * 0.2f + sin(angle).toFloat() * speed * t + 0.5f * size.height * 1.5f * t * t
            val fade = if (t > 0.75f) (1f - t) / 0.25f else 1f
            val s = (5f + 7f * p.c) * density
            rotate(degrees = t * 720f * (p.a + 0.5f), pivot = Offset(x, y)) {
                if (p.round) drawCircle(p.color.copy(alpha = fade), s / 2, Offset(x, y)) else drawRect(p.color.copy(alpha = fade), Offset(x - s / 2, y - s / 3), Size(s, s * 0.6f))
            }
        }
    }
}
private data class Piece(val a: Float, val b: Float, val c: Float, val d: Float, val color: Color, val round: Boolean)

/** A circle that draws itself, followed by a tick: the "saved" moment. */
@Composable
fun AnimatedCheck(modifier: Modifier = Modifier, color: Color = Studio.Teal, play: Boolean = true) {
    val on = rememberMotionOn()
    val p = remember { Animatable(if (on) 0f else 1f) }
    LaunchedEffect(play) { if (play && on) { p.snapTo(0f); p.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) } }
    Canvas(modifier) {
        val w = size.minDimension; val stroke = w * 0.07f
        val ring = (p.value / 0.6f).coerceIn(0f, 1f); val tick = ((p.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = 0.16f * ring), w / 2)
        drawArc(color, -90f, 360f * ring, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, w - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        if (tick > 0f) {
            val path = Path().apply { moveTo(w * 0.29f, w * 0.52f); lineTo(w * 0.44f, w * 0.67f); lineTo(w * 0.72f, w * 0.36f) }
            val seg = Path(); PathMeasure().also { it.setPath(path, false); it.getSegment(0f, it.length * tick, seg, true) }
            drawPath(seg, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A circular gradient progress ring with the percentage in the middle. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f; val inset = stroke / 2
            val tl = Offset(inset, inset); val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(stroke))
            rotate(-90f) {   // the gradient sweeps from 3 o'clock, so turn it to start at 12
                drawArc(Brush.sweepGradient(listOf(Studio.Violet, Studio.Coral, Studio.Amber, Studio.Violet)), 0f, 360f * shown.coerceAtLeast(0.02f), false, topLeft = tl, size = sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Expanding, fading rings behind the hero icon. */
@Composable
fun PulseRings(modifier: Modifier, color: Color) {
    val on = rememberMotionOn()
    val v by (if (on) rememberInfiniteTransition(label = "rings").animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "ringsV") else remember { mutableFloatStateOf(0f) })
    Canvas(modifier) {
        for (k in 0..2) {
            val f = if (on) (v + k / 3f) % 1f else k / 3f
            drawCircle(color.copy(alpha = (1f - f) * 0.5f), size.minDimension * (0.38f + 0.12f * f), style = Stroke(2.5f * density))
        }
    }
}
