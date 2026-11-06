package com.video.framora

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Any screen can ask for the Pro sheet with the reason the person hit ("GIFs up to 10 s"): `LocalProGate.current("...")`. */
val LocalProGate = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Small "PRO" pill that marks a locked item. */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Text("PRO", modifier.clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Studio.Violet, Studio.Coral))).padding(horizontal = 6.dp, vertical = 1.dp),
        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
}

/** Slim, dismiss-free promo on the home screen for people who have not bought Pro. Only shown when payments are set up. */
@Composable
fun GoProCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).flowingGradient(ThemePrefs.active.hero.reversed(), 12_000).bounceClick(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.WorkspacePremium, null, Modifier.size(30.dp), tint = Color.White)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Go Pro", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("No ads · no tag · High quality · more", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.92f))
        }
        Text(Payments.config.displayPrice + " once", Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

private sealed interface Phase {
    data object Idle : Phase
    data object Opening : Phase
    data object Confirming : Phase
    data class Message(val text: String, val good: Boolean = false) : Phase
}

/** The Pro upgrade sheet: price, a clear free-versus-Pro table, the payment, and restore. [reason] says what the person tried to do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSheet(reason: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val config = Payments.config
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var restoreOpen by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        Payments.coordinator.retryUnverified()
        Payments.coordinator.events.collect { e ->
            phase = when (e) {
                PaymentEvent.ResultReceived -> Phase.Confirming
                is PaymentEvent.Unlocked -> Phase.Idle
                PaymentEvent.Cancelled -> Phase.Message("Payment cancelled. You haven't been charged.")
                is PaymentEvent.Failed -> Phase.Message(e.message)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                // The details scroll; the pay button below stays in view the whole time, so buying never means hunting for it.
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (Pro.isPro) {
                        ProThanks(onDismiss)
                    } else {
                        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                            PulseRings(Modifier.fillMaxSize(), Studio.Violet)
                            Sparkles(Modifier.fillMaxSize(), count = 8)
                            Box(Modifier.floating(5.dp, 2800).size(78.dp).clip(CircleShape).background(Studio.tile(Studio.Violet, Studio.Coral)).sheen(strength = 0.3f), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.WorkspacePremium, null, Modifier.size(40.dp), tint = Color.White)
                            }
                        }
                        Text("Framora Pro", style = MaterialTheme.typography.headlineLarge)
                        if (!reason.isNullOrBlank()) Text("To use: $reason", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(config.displayPrice, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 48.sp))
                            Text("one time · yours for life · no subscription", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (config.testMode) Text("TEST MODE: no real money moves", Modifier.clip(RoundedCornerShape(8.dp)).background(Studio.Amber.copy(alpha = 0.25f)).padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                        ProPreview()
                        Entitlements.table.forEach { r ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.Top) { Text("Free", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(r.free, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Row(verticalAlignment = Alignment.Top) { Text("Pro", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(r.pro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                        Text("Editing, saving and sharing stay free: Pro never locks your own photos or videos.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                if (!Pro.isPro) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val busy = phase == Phase.Opening || phase == Phase.Confirming
                    (phase as? Phase.Message)?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium, color = if (it.good) Studio.Teal else MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    Button({ phase = Phase.Opening; ctx.findActivity()?.let { Payments.coordinator.start(it) } }, Modifier.fillMaxWidth().height(56.dp), enabled = config.enabled && !busy, shape = RoundedCornerShape(18.dp)) {
                        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(if (phase == Phase.Confirming) "Confirming payment…" else "Opening payment…") }
                        else Text(if (config.enabled) "Get Pro for ${config.displayPrice}" else "Payments aren't set up yet", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton({ restoreOpen = true }) { Text("Restore purchase") }
                    Text("Pay with UPI, cards, netbanking or wallets through Razorpay. Only payment details go to Razorpay, never your photos or videos.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            if (Pro.isPro) Confetti(true, Modifier.matchParentSize(), seed = 7)
        }
    }

    if (restoreOpen) RestoreDialog(onDismiss = { restoreOpen = false }) { id -> Payments.coordinator.restore(id) }
}

@Composable
private fun ProThanks(onDismiss: () -> Unit) {
    AnimatedCheck(Modifier.size(96.dp))
    Text("You're Pro!", style = MaterialTheme.typography.headlineLarge)
    Text("Thank you for supporting Framora. Ads are gone and every Pro tool is unlocked, for life.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Button(onDismiss, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Start creating") }
}

@Composable
private fun RestoreDialog(onDismiss: () -> Unit, restore: suspend (String) -> String) {
    var id by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Restore purchase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("If Google backup was on, Pro comes back by itself after you reinstall. Otherwise enter the payment id from your Razorpay receipt (it starts with pay_).", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(id, { id = it.trim() }, singleLine = true, label = { Text("Payment id") }, placeholder = { Text("pay_...") }, modifier = Modifier.fillMaxWidth())
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (Pro.isPro) Studio.Teal else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ working = true; scope.launch { result = restore(id); working = false } }, enabled = id.isNotBlank() && !working) { Text(if (working) "Checking…" else "Restore") } },
        dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Tells people BEFORE they export that the free version adds a small tag, and how to remove it. */
@Composable
fun ProTagNote(forGif: Boolean = false) {
    if (!Entitlements.FREE_EXPORTS_CARRY_TAG || !Payments.config.enabled) return
    val gate = LocalProGate.current
    TextButton({ gate("Exports without the \"${Entitlements.TAG_TEXT}\" tag") }, contentPadding = PaddingValues(0.dp)) {
        ProBadge(); Text("  Free ${if (forGif) "GIFs" else "videos"} carry a small \"${Entitlements.TAG_TEXT}\" tag. Remove it with Pro", style = MaterialTheme.typography.labelMedium)
    }
}

/** A look inside Pro: the templates, filters and stickers that unlock, so the price is easy to weigh. */
@Composable
private fun ProPreview() {
    val proTemplates = SlideshowTemplates.filter { it.pro }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What's inside", style = MaterialTheme.typography.titleSmall)
        Text("${proTemplates.size} Pro templates", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            proTemplates.forEach { t ->
                Column(Modifier.size(width = 112.dp, height = 74.dp).clip(RoundedCornerShape(14.dp)).background(Studio.tile(t.from, t.to)).padding(8.dp)) {
                    Icon(t.icon, null, Modifier.size(18.dp), tint = Color.White); Spacer(Modifier.weight(1f))
                    Text(t.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
                }
            }
        }
        val proFilters = PhotoFilter.entries.filter { it.pro }
        Text("${proFilters.size} Pro filters · ${LookPresets.count { it.filter.pro }} Pro looks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            proFilters.forEach { Text(it.label, Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium) }
        }
        Text("Plus: your own logo on exports · ${VoiceFx.entries.count { it.pro }} voice effects · ${CaptionStyle.entries.count { it.pro }} caption styles · ${FrameStyle.entries.count { it.pro }} photo frames · ${TextFont.entries.count { it.pro }} fonts · ${AppTheme.entries.count { it.pro }} app themes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("${StickerPacks.proPacks.size} Pro sticker packs · ${Motion.entries.count { it.pro }} Pro motions · ${CollageLayout.entries.count { it.pro }} Pro collage layouts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StickerPacks.proPacks.flatMap { it.emojis.take(4) }.forEach { Text(it, fontSize = 26.sp) }
        }
    }
}

/**
 * Pro: your own logo on every export. Free people see what it is and can open the Pro sheet; Pro people pick a picture
 * (kept in app storage), choose a corner and can remove it.
 */
@Composable
fun LogoSetting(logoUri: String?, corner: LogoCorner, onPick: (android.net.Uri?) -> Unit, onCorner: (LogoCorner) -> Unit) {
    val gate = LocalProGate.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onPick(u) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Your logo", style = MaterialTheme.typography.titleSmall); if (!Pro.isPro) { Spacer(Modifier.width(8.dp)); ProBadge() } }
        if (!Pro.isPro) {
            Text("Put your own logo on every video, slideshow and GIF you export.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ gate("Your own logo on every export") }) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add your logo") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.AddPhotoAlternate, null); Text(if (logoUri == null) " Choose logo" else " Change logo") }
                if (logoUri != null) TextButton({ onPick(null) }) { Text("Remove") }
            }
            if (logoUri != null) { Text("Corner", style = MaterialTheme.typography.labelMedium); ChipRow(LogoCorner.entries, corner, { it.label }, onCorner) }
        }
    }
}
