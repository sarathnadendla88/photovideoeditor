# CLAUDE.md — Offline-First Android Photo, Video & Music Studio

## 1. Project vision

Build a polished, offline-first Android app that lets everyday users create and edit photos, videos, slideshows, and social-media content directly on their devices.

**Quality bar:** develop this application as a **senior Android architect and experienced mobile creative-tools team** would. Deliver a premium, intuitive, visually excellent product—not merely a collection of screens. The UI must feel modern, coherent, responsive, delightful, and easy for first-time users while still exposing advanced controls for experienced creators.

**Feature completeness:** aim for feature breadth comparable to current leading mobile photo/video editors, while keeping the implementation realistic, legally compliant, maintainable, performant, and privacy-first. Research current apps and official documentation before finalizing the feature matrix. Do not copy proprietary branding, assets, or exact layouts. Do not claim that every feature in every competitor can be delivered immediately; prioritize, implement, test, and document each feature honestly.

The app should combine:
- Photo editing
- Video editing
- Music and audio editing
- Photo-to-music slideshow creation
- On-device AI effects where practical
- Social-media templates and export presets
- Local project saving and recovery
- Optional monetization through ads and Google Play purchases

**Core product principle:** users should be able to complete useful editing workflows without an account or our own backend server. Do not upload user media to a server. Any future network-based feature must be optional, clearly explained, and designed with privacy in mind.

This file is the project-level guidance for Claude Code. Treat it as the source of truth unless the user explicitly changes the product direction.

---

## 2. Product goals

1. Make common editing tasks approachable for non-professional users.
2. Provide reliable results on low-, mid-, and high-end Android devices.
3. Keep core editing workflows offline.
4. Support adding music to videos and creating videos from multiple photos.
5. Keep original media unchanged unless the user explicitly exports or overwrites it.
6. Use a modular architecture so editing engines and AI implementations can be replaced independently.
7. Build a useful free tier and introduce paid features without blocking basic editing.
8. Be transparent about device compatibility, processing time, permissions, and export limitations.

### Product research and competitive feature checklist

Before finalizing the roadmap, review current versions and official feature descriptions for leading Android creator apps (for example, CapCut, InShot, and VN Video Editor), plus relevant Android platform capabilities. Re-check this research periodically because features change.

Evaluate, where technically and legally appropriate:
- Multi-track timeline editing, clip splitting/trimming/reordering, transitions, overlays, picture-in-picture, keyframe animation, speed curves, freeze frame, reverse, and stabilization where feasible.
- Captions/subtitles, speech-to-text, text-to-speech/voiceover, audio extraction, beat markers, sound effects, noise reduction, and audio ducking where supported.
- Color correction/grading, LUT support where licensing permits, filters, masks, chroma key/green screen, blur, motion effects, and subject/background tools.
- Templates, auto-edit/story creation, reusable brand kits, animated text/stickers, titles, intro/outro cards, collages, and social aspect-ratio presets.
- Photo retouching, object/subject cutouts, background replacement, batch editing, and before/after comparison.
- Draft autosave, undo/redo, proxy/low-resolution preview for large projects, export queue/resume where feasible, and project recovery.
- Export controls for resolution, frame rate, bitrate/quality, format, audio settings, watermark policy, and estimated file size based on actual device/engine capabilities.
- Accessibility, onboarding, contextual tips, empty states, haptics where useful, tablet/foldable layouts where practical, and polished light/dark themes.

Initial research examples to validate against the latest installed/released versions (re-check before implementation):
- **CapCut:** prompt-led AI creation/story tools, automatic editing/clipping, transcript-based editing, captions, voiceover/text-to-speech, translation, and AI-assisted media creation. Reference: https://www.capcut.com/resource/ai-editing-the-capcut-way
- **InShot:** multi-clip trimming/merging, speed controls, music and voiceover, animated text/stickers, transitions, canvas/background tools, photo collage, HD/4K export options depending on support, and AI effects/speech-edit features. Reference: https://play.google.com/store/apps/details?id=com.camerasideas.instashot
- **VN Video Editor:** evaluate multi-track editing, curve speed control, transitions, filters, and its current export/template toolset using its current official listing and release notes.
- **Android platform:** evaluate current creator/media capabilities and APIs rather than assuming competitor features are available through Android APIs. Reference: https://blog.google/products-and-platforms/platforms/android/android-17-creator-features/

Create a **feature matrix** with columns for feature, competitor/reference evidence, user value, technical feasibility, licensing/privacy risks, priority, implementation status, and test coverage. Treat this list as a research checklist, not a claim that all features already exist or can safely be implemented in one release. Prioritize a useful MVP, then build the remaining features in planned increments. Clearly identify features that require a compatible device, downloadable model, internet connection, additional license, or future phase.

## 3. Non-goals and boundaries

- Do not build or assume a custom backend is available.
- Do not upload, analyze remotely, or retain user photos, videos, audio, or projects.
- Do not bundle copyrighted commercial songs, fonts, stickers, or templates without appropriate licenses.
- Do not promise that every AI effect will run on every device.
- Do not promise unsupported export resolutions, frame rates, codecs, or effects.
- Do not imitate another app's branding, proprietary assets, or exact UI.
- Do not add analytics or advertising SDKs without documenting their data collection and obtaining required consent.
- Do not implement deceptive ads, forced ad clicks, or misleading purchase flows.

---

## 4. Target users and key use cases

### Target users
- People creating Instagram Reels, YouTube Shorts, WhatsApp status videos, and other social posts
- Families creating birthday, baby milestone, wedding, anniversary, and travel slideshows
- Users who want simple photo adjustments and collages
- Users who want to trim videos, add text, and mix music without a desktop editor
- Users who prefer local editing and privacy

### Main user journeys
1. Pick photos → select a slideshow template → add music → adjust duration and transitions → preview → export MP4.
2. Pick a video → trim/split/crop → add music → adjust original and music volume → preview → export.
3. Pick a photo → adjust lighting and color → add text/stickers → save an edited copy.
4. Pick a subject photo → run compatible on-device background segmentation → replace background → export.
5. Open a draft → continue editing → export without losing prior work.
6. Use a template → replace placeholder media → customize text/music → export.
7. Share the exported result using Android's share sheet.

---

## 5. Feature scope

### 5.1 Photo editor
- Crop, rotate, flip, resize, and (where practical) perspective correction
- Brightness, contrast, exposure, saturation, temperature, tint, highlights, shadows, and vignette
- Filters and presets
- HSL and curves only if performance and UI complexity are manageable
- Text, stickers, frames, shapes, and drawing
- Collage layouts and adjustable spacing/borders
- Portrait blur and background effects when supported
- Background removal and replacement using a compatible on-device model
- Before/after comparison
- Undo/redo history
- Non-destructive edits where practical
- Export as a new file by default

### 5.2 Video editor
- Import and arrange multiple video clips
- Trim, split, reorder, merge, crop, rotate, and scale
- Supported speed controls, including slow motion where feasible
- Text, stickers, basic overlays, and filters
- Transitions supported by the chosen rendering implementation
- Canvas presets: 9:16, 1:1, and 16:9
- Preview and timeline scrubbing
- Export resolution, frame rate, and quality settings based on device support
- Progress, cancel, failure recovery, and retry
- Estimate output size where feasible
- Avoid loading entire source videos into memory

### 5.3 Music and audio studio — required core feature
- Select audio through Android's system picker / Storage Access Framework
- Add background music to a video
- Create a video slideshow from multiple photos and a music track
- Trim music to a selected segment
- Independently control original-video volume and added-music volume
- Mute original audio
- Fade-in and fade-out where supported by the editing pipeline
- Choose whether music stops with the video or loops to cover it
- Record and add voiceovers where practical
- Add multiple audio tracks only if the chosen engine supports reliable mixing
- Preview audio/video synchronization
- Show clear behavior when a selected audio file is unsupported or inaccessible
- Never bundle music without appropriate distribution rights

### 5.4 Slideshow maker
- Select multiple photos
- Reorder photos
- Set per-photo duration or a global duration
- Choose transitions and motion effects
- Add music and optionally sync transitions to detected beats
- Add titles/captions and an ending card
- Choose aspect ratio and export settings
- Include useful starter templates stored locally

### 5.5 On-device AI
Potential features, implemented incrementally:
- Subject/background segmentation
- Background blur and replacement
- Object/subject cutout
- Photo denoising or enhancement with a suitable model
- Automatic captions using a local speech model, if model size, licensing, latency, and device requirements are acceptable
- Face-aware effects only when privacy-safe, appropriately licensed, and technically justified

AI implementation rules:
- Prefer on-device inference.
- Verify model licenses and redistribution rights before shipping.
- Document model size, minimum practical device requirements, memory use, and expected latency.
- Downloadable models may require internet for initial setup; explain this clearly.
- Provide graceful fallbacks when a model is unavailable or inference fails.
- Never claim generative editing is available unless the feature is implemented and tested.
- Avoid blocking the main thread during inference.

### 5.6 Social creator tools
- Reels/Shorts/story canvas presets
- Birthday, family, travel, wedding, festival, and memory templates
- Reusable text styles, intros/outros, transitions, and effects
- Local template packs; no server dependency for bundled templates
- Share through Android's share sheet
- Keep templates customizable and do not require users to publish or upload content

### 5.7 Project and export management
- Save project metadata locally
- Restore unfinished projects after app restart or process death
- Keep source media references valid where possible and detect missing/moved files
- Use Android-supported URIs and persist URI permissions when needed
- Save exported files using MediaStore or the appropriate Android API
- Show export progress, completion, cancellation, and actionable errors
- Warn when storage is low
- Provide cleanup for app-owned temporary files without deleting user originals
- Use thumbnails and bounded caches

---

## 6. Monetization

Potential model:
- **Free tier:** basic photo editing, basic video editing, music import, slideshow creation, and standard export.
- **Rewarded ads:** optional access to selected premium effects or temporary premium use. Ads must never be required to perform a basic edit or export a user's completed project.
- **One-time purchase:** remove ads and unlock a clearly defined collection of premium tools.
- **Subscription (optional, only if ongoing value exists):** new premium templates/effects and ongoing feature updates.
- **Pricing and paywall UX:** design a transparent pricing screen with a clear free-versus-premium comparison, monthly/yearly plans only when justified, one-time purchase where appropriate, localized prices from the actual billing provider, renewal/cancellation terms, trial terms if any, restore-purchase action, and links to applicable terms/privacy policy. Never hardcode a price that could disagree with the provider.

### Razorpay integration requirements (only for an allowed payment flow)

If the product owner chooses Razorpay for a permitted purchase flow, implement and test it using **Razorpay Test Mode / sandbox credentials first**. Never invent credentials or put real secrets in source control.

- Research the current official Razorpay Android integration documentation before coding; verify the SDK and supported payment flow against current Android and Play policies.
- Use test Key IDs and test credentials supplied by the developer through local, untracked configuration or secure environment configuration. Use clearly named placeholders in documentation; do not fabricate working credentials.
- Keep any Key Secret and payment-signature verification on a trusted server. Never embed a Key Secret in the Android app, commit secrets, or treat a client-side success callback alone as proof of payment.
- Verify payment status/signature server-side, handle success, failure, cancellation, pending states, duplicate callbacks, network interruptions, and safe retries; grant entitlements only after verified payment.
- Add tests for test-mode success/failure flows and entitlement changes. Document a separate, deliberate production cutover checklist; never switch to live keys automatically.
- If the app is distributed through Google Play, use Google Play Billing for digital features/subscriptions where Play policy requires it. Do not use Razorpay to bypass store billing rules. Confirm current policy and any applicable regional/program exceptions before selecting a payment architecture.
- Payment and subscription functionality may require network access; the photo/video editing core must remain available offline as promised.

Rules:
- Use Google Play Billing for digital purchases distributed through Google Play, subject to current policies.
- Use an ad SDK such as Google Mobile Ads only after evaluating its current privacy and consent requirements.
- No deceptive paywalls, fake countdowns, hidden recurring charges, or accidental purchase flows.
- Provide restore-purchase handling where applicable.
- Do not gate privacy, basic export, or access to users' original files behind payment.
- Document SDK data collection, consent, and Play Console disclosures.
- Monetization services need connectivity; core editing should still work offline.

---

## 7. Recommended Android technology

Prefer stable, maintained versions compatible with the project's configured Android Gradle Plugin and Kotlin versions. **Do not invent or blindly upgrade dependency versions.** Inspect the repository and current official documentation before changing versions.

Potential stack:
- Kotlin
- Jetpack Compose and Material 3 for UI
- Coroutines and Flow for asynchronous work
- ViewModel for UI state and lifecycle-aware state collection
- Room for project metadata and local history
- DataStore for settings and lightweight preferences
- Android Photo Picker / Storage Access Framework for media selection
- MediaStore for exported media
- AndroidX Media3 Transformer / ExoPlayer for video composition, playback, and supported audio mixing
- Android graphics APIs, Canvas, shaders, and/or OpenGL where justified for photo effects
- ML Kit subject segmentation or MediaPipe / compatible local models only after validating current availability, licensing, device support, and offline behavior
- Google Play Billing for in-app purchases
- A suitable advertising SDK only if monetization is enabled and privacy requirements are met

Important:
- Check current Media3 composition and effect limitations before promising features such as crossfades, multi-track mixing, or complex transitions.
- If a required feature is not supported directly, design an abstraction and document the custom rendering work required.
- Do not add FFmpeg or other libraries without checking their license, binary size, maintenance status, and compatibility.
- Do not use a cloud AI API for a feature described as fully on-device.

---

## 8. Architecture principles

Use a modular, testable architecture. Adapt to the existing repository rather than generating a second competing architecture.

Suggested modules (only split into Gradle modules when project size justifies it):
- `app`
- `core:model`
- `core:common`
- `core:storage`
- `core:media`
- `core:designsystem`
- `feature:home`
- `feature:photo-editor`
- `feature:video-editor`
- `feature:audio`
- `feature:slideshow`
- `feature:ai-tools`
- `feature:projects`
- `feature:export`
- `feature:premium`

Suggested boundaries:
- UI: Compose screens, reusable components, navigation, UI state
- Domain: use cases and editing/project rules where complexity warrants it
- Data: Room, DataStore, URI/project persistence
- Media engine: interfaces for preview, render/export, audio mix, photo processing
- AI engine: interfaces for model availability, inference, progress, cancellation, and errors
- Monetization: isolated ads and billing integration

Principles:
- Keep UI composables free from heavy processing and business logic.
- Do not perform decoding, rendering, inference, file copying, or export on the main thread.
- Model long-running operations with progress, cancellation, and typed errors.
- Use dependency injection only if it improves testability and consistency with the repository; do not introduce unnecessary complexity.
- Keep editing operations and export settings serializable for draft recovery.
- Use a project timeline model rather than coupling edits directly to UI widgets.
- Keep original files immutable by default.
- Add clear abstractions around Media3 so limitations or engine changes do not leak throughout the app.
- Make unsupported effects fail gracefully with a clear explanation.

---

## 9. Performance and reliability

Media editing is memory- and CPU-intensive. Treat performance as a first-class requirement.

- Never decode full-resolution images just to display small thumbnails.
- Use downsampled previews and bounded caches.
- Avoid retaining full video frames or bitmaps unnecessarily.
- Use lifecycle-aware playback and release player/rendering resources correctly.
- Handle orientation, aspect ratio, variable frame rate, audio/video duration mismatch, and unsupported codecs.
- Support cancellation and cleanup for failed exports.
- Avoid blocking the UI while export or AI inference runs.
- Test low-memory devices, app backgrounding, process death, low storage, and interrupted exports.
- Handle MediaStore/URI permission loss and missing source media.
- Avoid claiming a fixed export speed; it depends on source media and hardware.
- Use Android-supported APIs and foreground execution mechanisms appropriate to long-running user-initiated exports and the target Android version.
- Do not use unbounded WorkManager jobs as a substitute for the right user-visible long-running processing approach.

---

## 10. Privacy, security, and permissions

- Request access only when a user initiates a relevant action.
- Prefer Android Photo Picker and Storage Access Framework over broad library permissions when suitable.
- Do not request microphone permission until the user chooses voice recording.
- Explain why each permission is needed.
- Store projects and temporary files in appropriate app-owned locations.
- Do not log file contents, private URIs, personal media metadata, or sensitive user text unnecessarily.
- Do not include media in crash reports or analytics.
- Clean up temporary files safely.
- Provide a privacy policy that accurately reflects every included SDK and feature.
- Complete Google Play Data safety and permissions declarations accurately.
- Avoid INTERNET permission unless an actual feature or SDK requires it; ads, billing, and model downloads may require network access.
- Do not describe the app as entirely offline if any included feature needs a network connection.

---

## 11. Accessibility and UX

### Visual design and usability bar

The application must look and feel like a high-quality, current-generation creative studio. Do not settle for default-looking screens, rough wireframes, inconsistent controls, or a basic form-based UI.

- Establish a cohesive design system: color tokens, typography scale, spacing, shape/radius rules, elevation, icon style, motion rules, and reusable components.
- Use modern Jetpack Compose and Material 3 patterns, customized with a recognizable visual identity. Keep screens elegant and focused; avoid excessive gradients, visual noise, and decorative effects that reduce clarity.
- Make the home screen immediately useful, with clear Create/Edit entry points, recent projects, templates, and discoverable tools.
- Build a practical editor layout: prominent preview canvas, usable timeline/track area, contextual tool panels, clear selected-state feedback, and accessible undo/redo and export actions.
- Design intuitive onboarding, permission explanations, first-project guidance, tooltips/contextual help, premium comparison, settings, project management, and export/share flows.
- Use subtle, purposeful transitions and animations; avoid animation that slows editing or harms accessibility.
- Provide thoughtful empty, loading, processing, success, failure, offline, permission-denied, unsupported-format, insufficient-storage, and model-unavailable states.
- Support light and dark themes, system font scaling, screen readers, adequate contrast, accessible touch targets, and reduced-motion preferences where applicable.
- Adapt gracefully to small and large phones, landscape editing, tablets, and foldables where practical.
- Show users what is free and premium before they invest time in a workflow. Never surprise users with a paywall at export after they have completed a project.
- Keep editing controls responsive during preview and processing. Use thumbnails, proxy previews, and contextual controls where needed.
- Use real working screens and end-to-end flows. Do not use fake project cards, fake export progress, placeholder purchase success, or controls that do nothing.

Before marking UI work complete, review each primary journey on representative screen sizes, inspect screenshots, verify visual consistency, and fix obvious spacing, clipping, contrast, navigation, and state-feedback issues.

- Use clear labels and touch targets.
- Support dark and light themes.
- Show editing progress and meaningful error messages.
- Provide undo/redo for destructive-feeling operations.
- Avoid hidden gestures as the only way to use important features.
- Make audio controls understandable: music volume, original audio volume, mute, trim, and fade.
- Show a clear preview before export.
- Keep primary actions visible and minimize unnecessary steps.
- Respect system font scaling and accessibility settings.
- Make loading, empty, unsupported-format, and error states intentional.
- Use consistent Material 3 design and avoid visual clutter.

---

## 12. Testing requirements

Use unit tests for domain logic and project/timeline calculations, plus instrumented tests for Android media integration.

Maintain a living **feature acceptance checklist** based on the competitive feature matrix and project roadmap. Every item must be marked as implemented and tested, partially implemented, planned, unsupported with a reason, or blocked by a dependency/license/platform limitation. Do not silently omit requested features. When a feature is deferred, record why, its dependency, and a realistic next step. A screen alone does not count as implementation.

At minimum, test:
- Selecting photos, videos, and audio through system pickers
- URI permission persistence and missing-file behavior
- Photo edit operations and undo/redo
- Video trim and clip ordering
- Music trimming and volume control
- Music longer/shorter than the video
- Muted source audio and voiceover/audio mixing where supported
- Slideshow photo ordering, durations, and transitions
- Export success, cancellation, failure, and retry
- Draft recovery after app restart
- Low storage and low memory
- Unsupported file formats/codecs
- Rotation and different aspect ratios
- Billing cancellation, pending, restored, and failed purchase states
- Ads not blocking core workflows
- Offline behavior with connectivity disabled
- Privacy checks ensuring media is not uploaded

Do not claim a feature is complete because its screen exists. A feature is complete only when its underlying operation works and is tested.

---

## 13. Development roadmap

### Phase 0 — Repository and technical validation
1. Inspect the current repository and identify existing conventions.
2. Check Gradle, Kotlin, Android Gradle Plugin, minSdk, targetSdk, and current dependencies.
3. Create a small proof of concept for Media3 video export with music mixing.
4. Validate the chosen photo processing and on-device segmentation approach.
5. Document Media3 limitations, model licensing, and device constraints before building the full UI.

### Phase 1 — MVP
- Home screen and media picker
- Basic photo editor with crop, rotate, and adjustments
- Slideshow creator from multiple photos
- Add local music to slideshow
- Basic video trim/crop and add music
- Preview and MP4 export
- Local draft/project saving
- Error, progress, and cancellation handling

### Phase 2 — Creator features
- Text and stickers
- Collage maker
- More filters and transitions
- Music trim, fades, original-audio volume, and voiceover
- Aspect-ratio presets and export options
- Local templates and share sheet

### Phase 3 — On-device AI and polish
- Background segmentation/removal
- Background replacement
- Selected enhancement models
- Beat-sync slideshow
- Auto-edit draft generation
- Performance improvements and broader device testing

### Phase 4 — Monetization and launch
- Optional rewarded ads
- Google Play Billing and premium entitlements
- Privacy policy and store disclosures
- Store listing, screenshots, and release testing
- Crash monitoring and opt-in/appropriate analytics
- User feedback and retention improvements

Do not attempt all phases at once. Keep the MVP shippable and validate user interest before expanding scope.

---

## 14. Claude Code working instructions

When working in this repository:

### Required senior-architect workflow

1. Act as a senior Android architect: evaluate trade-offs, platform constraints, maintainability, testability, security, accessibility, and performance before choosing an implementation.
2. Research current competitor capabilities and official Android/library/payment documentation when the task depends on changing information. Record source links and the date checked in a short technical note; do not rely on memory alone for current SDK APIs or app features.
3. Create and maintain a feature inventory so no requested feature is forgotten. Map each feature to its screen, domain behavior, persistence needs, engine capability, permissions, tests, and monetization status.
4. Design the UI to a high visual standard before scaling implementation: define design tokens and reusable Compose components, then validate key journeys with previews/screenshots or device runs where available.
5. Prefer complete vertical slices (UI + domain + persistence/engine + error states + tests) over disconnected screens. A control must work, or be visibly disabled and explained.
6. Implement all agreed features across planned phases, not all at once. Do not stop after a superficial MVP while presenting the application as feature-complete. At every milestone, report what is done, what remains, and what is blocked.
7. Before adding a dependency or advanced media feature, verify compatibility, maintenance, licensing, device constraints, binary size, and actual API support. Offer a fallback where reasonable.
8. For Razorpay, use test credentials supplied by the developer, protect secrets, verify payment server-side, and check Google Play policy before implementation. Never claim test credentials are live credentials or that a payment is verified solely from a client callback.


1. **Inspect first.** Read the existing project structure and relevant files before proposing changes.
2. **Plan substantial work.** For multi-file or architecture changes, outline the plan and likely files first.
3. **Implement in small steps.** Prefer a compiling, testable increment over large speculative rewrites.
4. **Respect existing conventions.** Do not rename packages, change app identity, or replace architecture without a reason.
5. **Verify dependencies.** Check current official documentation and the repository's compatibility before adding or updating libraries.
6. **Do not invent APIs.** Confirm the actual API signatures for the configured dependency versions.
7. **Preserve offline-first behavior.** Never add a backend or cloud processing by assumption.
8. **Protect user media.** Avoid uploads and do not modify original files by default.
9. **Handle failures.** Include loading, progress, cancellation, empty states, and recoverable errors for long-running operations.
10. **Test changes.** Run appropriate unit tests and build/test tasks where the environment permits. Report exactly what ran and what did not.
11. **Be honest about completion.** Clearly separate implemented, partially implemented, stubbed, and planned functionality.
12. **Avoid placeholder features disguised as complete.** A button must perform its advertised action or be clearly disabled/labeled as not yet available.
13. **Explain trade-offs.** Highlight performance, licensing, compatibility, and monetization implications when relevant.
14. **Keep documentation current.** Update README and relevant technical notes when architecture, permissions, dependencies, or workflows change.
15. **Never expose secrets.** Do not commit signing keys, API keys, service credentials, or local machine secrets.

### Definition of done for a feature
A feature is done only when:
- The UI and actual processing behavior are connected.
- Loading, success, failure, and cancellation states are handled where relevant.
- The main path and important edge cases are tested.
- Media permissions and privacy implications are addressed.
- The feature works on a representative range of devices or has clearly documented requirements.
- No unsupported capability is claimed in the UI or documentation.

---

## 15. First task for Claude Code

Start by inspecting the repository and report:
1. Existing project structure and architecture
2. Kotlin, Gradle, Android Gradle Plugin, minSdk, and targetSdk versions
3. Existing media, image-processing, AI, persistence, and billing dependencies
4. Any compatibility or licensing concerns
5. A minimal implementation plan for the MVP
6. A current competitor feature matrix for relevant leading Android photo/video editors, with official/source links and a checked date
7. A complete feature inventory divided into MVP, next releases, advanced/AI, and monetization, with dependencies and test criteria
8. A UI/UX design-system proposal and a list of the primary screens/journeys that must meet the premium-quality visual bar
9. A payment architecture recommendation that checks Google Play policy and explains whether Razorpay is appropriate for each proposed purchase type

Then implement **Phase 1 incrementally**, starting with a proof of concept that creates an MP4 slideshow from selected photos and adds a user-selected local music track. Verify the actual Media3 API capabilities in the project's dependency version before implementing the audio composition path.

Do not implement every feature in one pass. Build, test, and document each milestone.


---

## 16. Final quality gate — no forgotten features

Before each release milestone, Claude Code must:
- Compare the implementation against the complete feature inventory and identify every unimplemented, partially implemented, or untested feature.
- Verify all primary journeys end-to-end: import media, edit, preview, save/reopen draft, export, and share.
- Verify that the UI is visually polished, consistent, responsive, accessible, and usable without hidden gestures.
- Test free and premium states, payment cancellation/failure/pending/success, entitlement restoration, and offline editing where applicable.
- Confirm privacy, permission, copyright/license, Play Store, and payment-policy requirements.
- Run available builds and tests, report the exact commands/results, and never claim checks that were not run.
- Provide a release report with **Implemented**, **Partially implemented**, **Planned**, **Blocked**, and **Tested** lists.

The goal is comprehensive, competitive feature coverage and excellent UX over the product roadmap—not an unrealistic promise that every competitor feature can be delivered instantly or supported on every device. Never sacrifice user data safety, stability, honest pricing, or maintainability to inflate the feature list.