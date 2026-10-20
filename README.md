# Photo Video Studio (Phase 1 proof of concept)

Offline-first Android slideshow maker. Product direction lives in `claude.md`.

## Status (checked 2026-10-20; "verified" = run on the Android 16 emulator via adb)
| Area | State |
|---|---|
| Design system (tokens, gradients, tool-tab editor layout, empty states), edge-to-edge, light/dark | Implemented, verified in light theme |
| Home: new-slideshow hero, tool tiles, 5 slideshow templates, draft shortcut | Implemented, verified |
| Slideshow: photos, reorder/remove, templates, motion (zoom drift / slide / pop), opening + ending titles, music with volume/start/loop, canvas + quality, MP4 export, draft restore | Implemented; export with title verified, motion not frame-inspected |
| Video: live preview, multi-clip trim/split/reorder, per-clip speed 0.25–3×, filters + brightness/contrast/saturation, text caption, music, voiceover recording, mute/volume, canvas + quality, export | Implemented; speed (7.7 s → 3.85 s), text/filter and voiceover exports verified; multi-clip and music not re-tested |
| Photo: adjust (7 sliders), 11 filters, crop ratios, rotate/flip, text colour, emoji stickers, finger drawing, border, undo/redo, before/after, save copy | Implemented; sepia, border, sticker, drawing, save verified |
| Portrait blur / colour / remove (ML Kit, on device) | Runs without error; **not validated on a real person photo** (test image was a cartoon) |
| Camera (CameraX): photo, video, Reel mode (up to 60 s in segments, delete last clip, auto-stop at limit), front/back, flash, 3 s/10 s timer, hand-off to the photo/video editors | Implemented; photo→editor, Reel recording (two clips + 60 s auto-stop) and hand-off verified on the emulator's virtual camera. Not tried on a physical camera, front camera, timer or flash |
| Text-to-speech voiceover (device TTS engine) | Implemented; verified on the emulator (3 s WAV generated) |
| Trend features: styled timed captions (5 styles × 4 animations, position, size), emoji stickers on video, speed ramps (Hero time / Pulse / Montage), voice effects, one-tap video looks, "Export for" platform presets, photo auto-enhance, saved looks + batch apply, share after save, camera teleprompter | Implemented. Verified on the emulator: caption + speed-ramp export (output length matches the ramp, caption visible in the exported file), teleprompter overlay. Not yet exercised on device: sticker export, voice-effect export, saved looks / batch apply, auto-enhance |
| More: fade-in/dip-to-black transitions, music fade in/out (explicit repeated music items so the fade lands on the end), watermark/credit tag, free-form crop with draggable handles + ratio shapes, "Your creations" gallery (open/share/delete), first-run intro, Settings & privacy with temp-file cleaner | Implemented. Verified on the emulator: slideshow export with watermark + music + fade-in + dip setting (7.57 s, video+audio, watermark visible), free-form crop, intro, creations row (shows files saved by the current install). Dip-to-black and music fade-out not frame/ear-verified; FadeAudioProcessor unit-tested |
| Text, stickers & pictures everywhere: photo editor (many text layers with fonts/colour/box/rotation, emoji stickers, your own pictures, drag on the photo), video & Reels (timed text with free position, timed emoji stickers and your own pictures, WYSIWYG overlay preview you can drag), slideshow (a caption per photo, stickers/pictures on every photo, draggable) | Implemented. Verified on the emulator: photo text add + drag; video text add + drag then export shows it at the dragged spot; slideshow caption + 🔥 sticker in the exported file; picture overlay on the slideshow preview. Pictures/stickers/text no longer get cut at the frame edge (anchors and clamping match the preview); verified for a picture in the slideshow export and in the photo editor preview. Overlay pictures are copied into app storage so they survive restarts. Not yet exercised: picture overlay in an exported video-editor clip, timed windows in export |
| Take photo / record video from inside each tool (photo editor, video editor, slideshow, collage) next to Pick/Add; camera opens in the matching mode and returns the capture to where you were | Implemented. Verified on the emulator: photo editor (take → edit), video editor (record → clip added), slideshow (take → 4th photo). Collage uses the same path but was not exercised |
| Collage: 8 layouts, 4 shapes, spacing, corners, background, tap cell to swap, save | Implemented, verified |
| Unit tests | 12 passing (project models, undo stack, speed/split/trim, canvas sizes, templates) |
| Audio fades, crossfade transitions, reverse/freeze, keyframes, stickers on video, auto-captions, beat sync | Planned / needs custom rendering or models |
| Billing, ads, Razorpay, premium paywall | Not started (needs a payment-architecture decision) |
| Export in a foreground service, multi-project drafts (Room) | Planned |

Known limits: the video preview plays music and voiceover in step with the clips (separate players), so tiny sync drift is possible and clip speed is not reflected; video filter presets use their linear colour part only (offsets cannot be expressed in Media3's RGB matrix); the video preview shows overlays on the export canvas but not speed changes (overlay timing in the preview ignores speed), and; the slideshow preview is a still approximation.

## Stack
Kotlin 2.2.20, AGP 8.13.2, Gradle 8.13, Compose BOM 2025.09.00, Media3 1.8.1 (transformer/effect), CameraX 1.5.1, Coil 2.7.0, ML Kit selfie segmentation 16.0.0-beta6 (bundled model, offline). minSdk 26, target 35.
Permissions: CAMERA and RECORD_AUDIO (asked when you open the camera or tap Record voiceover). No INTERNET permission; no analytics/ads SDKs.

## Build / test
    ./gradlew :app:assembleDebug :app:testDebugUnitTest
