package com.sarath.photovideo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** A bottom-tool-bar item, as in pro mobile editors: icon over label, clear selected state. */
data class ToolTab(val label: String, val icon: ImageVector)

@Composable
fun ToolTabBar(tabs: List<ToolTab>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { i, t -> TabItem(t, i == selected) { onSelect(i) } }
    }
}

@Composable
private fun TabItem(t: ToolTab, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(220), label = "tabBg")
    val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "tabFg")
    val lift by animateFloatAsState(if (on) 1.16f else 1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "tabLift")
    Column(
        Modifier.widthIn(min = 72.dp).clip(Studio.ChipShape).background(bg).bounceClick(Role.Tab, onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon, null, Modifier.graphicsLayer { scaleX = lift; scaleY = lift }, tint = fg)
        Text(t.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = fg)
    }
}

/** Labelled slider that commits once per gesture (keeps undo history clean and dragging smooth). */
@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String = "%.2f".format(value), onCommit: (Float) -> Unit) {
    var live by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(if (live == value) valueText else "%.2f".format(live), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(live, { live = it }, valueRange = range, onValueChangeFinished = { onCommit(live) })
    }
}

@Composable
fun <T> ChipRow(items: List<T>, selected: T?, name: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FilterChip(it == selected, { onPick(it) }, { Text(name(it)) }, shape = Studio.ChipShape) }
    }
}

/** A chip row where some choices are Pro-only: they show a PRO badge and open the Pro sheet instead of selecting. */
@Composable
fun <T> ProChipRow(items: List<T>, selected: T?, name: (T) -> String, locked: (T) -> Boolean, reason: String, onPick: (T) -> Unit) {
    val gate = LocalProGate.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val isLocked = locked(item)
            FilterChip(item == selected, { if (isLocked) gate(reason) else onPick(item) },
                { Row(verticalAlignment = Alignment.CenterVertically) { Text(name(item)); if (isLocked) { Spacer(Modifier.width(6.dp)); ProBadge() } } }, shape = Studio.ChipShape)
        }
    }
}

@Composable
fun GradientCard(brush: Brush, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(Studio.CardShape).background(brush).sheen(periodMs = 5200, strength = 0.16f).bounceClick(onClick = onClick).padding(16.dp),
        content = content,
    )
}

/** A slowly flowing two/three colour gradient behind the content. */
fun Modifier.flowingGradient(colors: List<Color>, periodMs: Int = 9000): Modifier = composed {
    val on = rememberMotionOn()
    val f by (if (on) rememberInfiniteTransition(label = "flow").animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "flowV") else remember { mutableFloatStateOf(0f) })
    this.drawBehind {
        val shift = f * size.width
        drawRect(Brush.linearGradient(colors, Offset(-shift, 0f), Offset(size.width * 1.1f - shift, size.height), tileMode = TileMode.Mirror))
    }
}

/** The big call-to-action cards on the home screen: flowing gradient, sparkles, a floating icon and a light sweep. */
@Composable
fun HeroCard(colors: List<Color>, icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, phase: Float = 0f, onClick: () -> Unit) {
    Box(modifier.clip(Studio.CardShape).flowingGradient(colors).sheen(strength = 0.2f).bounceClick(onClick = onClick)) {
        Sparkles(Modifier.matchParentSize(), count = 14)
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Box(Modifier.floating(5.dp, 2600, phase).size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(26.dp), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(subtitle, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelMedium, minLines = 2, maxLines = 2)
        }
    }
}

/** Dark glass tile with a glowing gradient icon chip: the tool grid on the home screen. */
@Composable
fun GlassTile(icon: ImageVector, title: String, sub: String, a: Color, b: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f))
            .border(1.dp, Brush.linearGradient(listOf(a.copy(alpha = 0.6f), Color.Transparent, b.copy(alpha = 0.4f))), shape)
            .bounceClick(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).drawBehind { drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.45f), Color.Transparent)), size.minDimension * 0.95f) }
            .clip(RoundedCornerShape(14.dp)).background(Studio.tile(a, b)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(CircleShape).background(Studio.tile(Studio.Violet, Studio.Coral)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit, secondAction: String? = null, onSecond: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            PulseRings(Modifier.fillMaxSize(), Studio.Violet)
            Sparkles(Modifier.fillMaxSize(), count = 10)
            Box(Modifier.floating(6.dp, 2800).size(92.dp).clip(CircleShape).background(Studio.tile(Studio.Violet, Studio.Coral)).sheen(strength = 0.3f), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(44.dp), tint = Color.White)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Button(onAction, Modifier.fillMaxWidth(0.8f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(action) }
        if (secondAction != null && onSecond != null) OutlinedButton(onSecond, Modifier.fillMaxWidth(0.8f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text(secondAction) }
    }
}
