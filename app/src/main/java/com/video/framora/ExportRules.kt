package com.video.framora

/**
 * What an export actually contains for a free or Pro user. Run on the project right before exporting, so a stale setting
 * (a draft made while Pro, a state that outlived a reset) can never produce a Pro-only result for a free user.
 * Free users get the nearest free equivalent; Pro users' projects pass through unchanged apart from the free-tag flag.
 */
fun VideoProject.forExport(pro: Boolean): VideoProject = copy(
    quality = Entitlements.cap(quality, pro),
    brandTag = Entitlements.brandTag(pro),
    clips = clips.map { if (Entitlements.speed(it.speed, pro)) it else it.copy(speed = it.speed.coerceIn(0.25f, 3f)) },
    originalVoice = if (Entitlements.voiceFx(originalVoice, pro)) originalVoice else VoiceFx.NORMAL,
    voiceover = voiceover?.let { if (Entitlements.voiceFx(it.fx, pro)) it else it.copy(fx = VoiceFx.NORMAL) },
    transition = if (Entitlements.transition(transition, pro)) transition else Transition.DIP,
    captionStyle = if (Entitlements.captionStyle(captionStyle, pro)) captionStyle else CaptionStyle.BOXED,
    filter = if (Entitlements.filter(filter, pro)) filter else PhotoFilter.NONE,
    logoUri = logoUri.takeIf { pro },
)

fun SlideshowProject.forExport(pro: Boolean): SlideshowProject = copy(
    quality = Entitlements.cap(quality, pro),
    brandTag = Entitlements.brandTag(pro),
    motion = if (Entitlements.motion(motion, pro)) motion else Motion.KEN_BURNS,
    transition = if (Entitlements.transition(transition, pro)) transition else Transition.DIP,
    logoUri = logoUri.takeIf { pro },
)

/** A photo edit with every Pro-only choice replaced by the free default. */
fun PhotoEdit.forSave(pro: Boolean): PhotoEdit = copy(
    filter = if (Entitlements.filter(filter, pro)) filter else PhotoFilter.NONE,
    effect = if (Entitlements.effect(effect, pro)) effect else PhotoEffect.NONE,
    frameStyle = if (Entitlements.frame(frameStyle, pro)) frameStyle else FrameStyle.PLAIN,
    texts = texts.map { if (Entitlements.font(it.font, pro)) it else it.copy(font = TextFont.SANS) },
)
