package com.sarath.photovideo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/** Emoji stickers grouped into packs. Classic is free; the rest are ReelMint Pro. Only widely supported emoji (Android 8 and up). */
data class StickerPack(val name: String, val emojis: List<String>, val pro: Boolean)

object StickerPacks {
    val all = listOf(
        StickerPack("Classic", listOf("😀", "😍", "🎉", "🎂", "❤️", "🔥", "⭐", "🌈", "🌸", "🐶", "🐱", "🎈", "👑", "🕶️", "🎵", "✈️"), false),
        StickerPack("Party", listOf("🎊", "🎁", "🍾", "🥂", "🎤", "🎶", "🎸", "🥁", "✨", "🎆", "🎇", "🍭", "🎀", "🎹", "🎧", "🕺", "💃", "🍰"), true),
        StickerPack("Love", listOf("🧡", "💛", "💚", "💙", "💜", "💖", "💕", "💞", "😘", "💌", "💍", "🌹", "💐", "😻", "💋", "💓", "💝", "🤗"), true),
        StickerPack("Travel", listOf("🚗", "🏖️", "🏝️", "🗺️", "🏔️", "🌅", "🌄", "⛺", "🚂", "🚢", "🗽", "🗼", "🌍", "📸", "🎒", "🚲", "🛫", "🛬"), true),
        StickerPack("Food", listOf("🍕", "🍔", "🍟", "🌮", "🍣", "🍜", "🍩", "🍦", "🍎", "🍓", "🍉", "🍇", "☕", "🍺", "🍷", "🥗", "🍪", "🥑"), true),
        StickerPack("Animals", listOf("🐼", "🦁", "🐯", "🐵", "🐸", "🦊", "🐰", "🐻", "🦄", "🐢", "🐬", "🦋", "🐝", "🐥", "🐧", "🦉", "🐘", "🐙"), true),
        StickerPack("Sports", listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏓", "🥊", "🏆", "🥇", "🏅", "🎯", "🏋️", "🚴", "🏊", "🧘", "⛳", "🎳"), true),
        StickerPack("Faces", listOf("😂", "🤣", "😎", "🤩", "😜", "🤪", "😇", "😢", "😭", "😡", "🤯", "😱", "🤔", "😴", "🙌", "👏", "👍", "💪"), true),
        StickerPack("Nature", listOf("🌺", "🌻", "🌴", "🌵", "🍀", "☀️", "🌙", "🌟", "⚡", "❄️", "🌊", "🍂", "🍁", "🌿", "🌼", "🌷", "🌾", "⛅"), true),
    )
    val classic get() = all.first()
    val proPacks get() = all.filter { it.pro }
}

/** Pack chooser (Pro packs are badged and open the Pro sheet) above the emoji of the chosen pack. Used by the photo, video and slideshow editors. */
@Composable
fun StickerShelf(onPick: (String) -> Unit) {
    var packIndex by remember { mutableIntStateOf(0) }
    val pack = StickerPacks.all[packIndex]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProChipRow(StickerPacks.all, pack, { it.name }, { !Entitlements.stickerPack(it, Pro.isPro) }, "Pro sticker packs") { packIndex = StickerPacks.all.indexOf(it) }
        ChipRow(pack.emojis, "", { it }) { onPick(it) }
    }
}
