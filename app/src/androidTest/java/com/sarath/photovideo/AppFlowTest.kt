package com.sarath.photovideo

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitText(text: String, timeoutMs: Long = 30_000, substring: Boolean = true) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

private fun androidx.compose.ui.test.junit4.ComposeTestRule.tapText(text: String, substring: Boolean = true) {
    // Invoke the click action directly: it does not depend on where the node ends up after scrolling.
    onAllNodesWithText(text, substring = substring).onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
}

/** Bottom tool-tab (exact label); the tab bar scrolls horizontally so scroll to it first. */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.tab(label: String) {
    onAllNodesWithText(label, substring = false).onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
}

/** Whole-app navigation: every tile opens its screen, back returns home, Settings works, shared files open the right editor. */
class HomeNavigationTest {
    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
    private val ctx get() = TestUtil.ctx

    @Before fun fresh() {
        ctx.getSharedPreferences("app", 0).edit().putBoolean("intro_done", true).commit()
        File(ctx.filesDir, "slideshow_draft").deleteRecursively()
    }

    private fun launch(intent: Intent? = null): ActivityScenario<MainActivity> =
        ActivityScenario.launch(intent ?: Intent(ctx, MainActivity::class.java))

    private fun back(s: ActivityScenario<MainActivity>) = s.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test fun homeShowsEveryTool() {
        launch().use {
            rule.waitText("ReelMint")
            listOf("Create a Reel", "New slideshow", "Camera", "Photo", "Video", "Collage", "Portrait", "Voiceover", "Ringtone").forEach { t ->
                rule.onAllNodesWithText(t).onFirst().performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test fun everyTileOpensItsScreenAndBackGoesHome() {
        launch().use { s ->
            rule.waitText("ReelMint")
            val cases = listOf("Photo" to "Start with a photo", "Video" to "Add your videos", "Collage" to "Make a collage",
                "Ringtone" to "Make your own ringtone", "New slideshow" to "Turn photos into a video", "Portrait" to "Start with a photo", "Voiceover" to "Add your videos")
            for ((tile, expect) in cases) {
                rule.onAllNodesWithText(tile).onFirst().performScrollTo().performClick()
                rule.waitText(expect, 15_000)
                back(s); rule.waitText("ReelMint")
            }
        }
    }

    @Test fun cameraOpensWithAllThreeModes() {
        launch().use { s ->
            rule.waitText("ReelMint")
            rule.onAllNodesWithText("Camera").onFirst().performScrollTo().performClick()
            rule.waitUntil(20_000) { rule.onAllNodesWithText("Reel").fetchSemanticsNodes().isNotEmpty() }
            listOf("Photo", "Video", "Reel").forEach { rule.onAllNodesWithText(it).onFirst().assertExists() }
            back(s); rule.waitText("ReelMint")
        }
    }

    @Test fun createReelOpensCameraInReelMode() {
        launch().use { s ->
            rule.waitText("ReelMint")
            rule.onAllNodesWithText("Create a Reel").onFirst().performClick()
            rule.waitUntil(20_000) { rule.onAllNodesWithText("clips", substring = true).fetchSemanticsNodes().isNotEmpty() }
            back(s)
        }
    }

    @Test fun settingsDialogShowsPrivacyAndAdsInfo() {
        launch().use {
            rule.waitText("ReelMint")
            rule.onNodeWithContentDescription("Settings and privacy").performClick()
            rule.waitText("Settings & privacy")
            rule.waitText("Google AdMob", substring = true)
            rule.onNodeWithText("Clear temporary files", substring = true).assertExists()
            rule.onNodeWithText("Show the intro again").assertExists()
        }
    }

    @Test fun introCanBeReplayedAndSkipped() {
        launch().use {
            rule.waitText("ReelMint")
            rule.onNodeWithContentDescription("Settings and privacy").performClick()
            rule.onNodeWithText("Show the intro again").performClick()
            rule.waitText("Create anything"); rule.onNodeWithText("Next").performClick()
            rule.waitText("Private by design"); rule.onNodeWithText("Skip").performClick()
            rule.waitText("ReelMint")
        }
    }

    @Test fun sharedVideoOpensVideoEditor() {
        val i = Intent(Intent.ACTION_SEND).setClassName(ctx, MainActivity::class.java.name).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, TestUtil.video)
        launch(i).use { rule.waitText("1 clips", 30_000, substring = true) }
    }

    @Test fun sharedImageOpensPhotoEditor() {
        val i = Intent(Intent.ACTION_SEND).setClassName(ctx, MainActivity::class.java.name).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, TestUtil.photos[0])
        launch(i).use { rule.waitText("Auto enhance", 30_000) }
    }

    @Test fun sharedImagesOpenSlideshow() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setClassName(ctx, MainActivity::class.java.name).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(TestUtil.photos))
        launch(i).use { rule.waitText("3 photos", 30_000, substring = true) }
    }

    @Test fun garbageShareDoesNotCrash() {
        val i = Intent(Intent.ACTION_SEND).setClassName(ctx, MainActivity::class.java.name).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
        launch(i).use { rule.waitText("ReelMint") }
    }
}

/** Photo editor, collage, slideshow and video editor driven through their real UI with injected media. */
class ScreenFlowTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = TestUtil.ctx.applicationContext as Application

    @Before fun clean() { File(TestUtil.ctx.filesDir, "slideshow_draft").deleteRecursively() }

    // ---------- Photo editor ----------
    private fun openPhoto() {
        rule.setContent { StudioTheme { PhotoEditorScreen(initialUri = TestUtil.photos[0], onTakePhoto = {}, onBack = {}) } }
        rule.waitText("Auto enhance")
    }

    @Test fun photoEditorTabsAllOpen() {
        openPhoto()
        mapOf("Filters" to "My looks", "Crop" to "Rotate", "Text" to "Add text", "Stickers" to "Add your own picture",
            "Draw" to "Draw on the photo", "Frame" to "Border width", "Portrait" to "on your device", "Adjust" to "Brightness").forEach { (tab, expect) ->
            rule.tab(tab)
            rule.waitText(expect, 10_000, substring = true)
        }
    }

    @Test fun photoFilterUndoRedoAndCompare() {
        openPhoto()
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.tab("Filters")
        rule.tapText("Sepia", substring = false)
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Redo").assertIsEnabled()
        rule.onNodeWithContentDescription("Redo").performClick()
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
    }

    @Test fun photoAutoEnhanceCropRotateAndFlip() {
        openPhoto()
        rule.onNodeWithText("Auto enhance").performClick()
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
        rule.tab("Crop")
        rule.tapText("1:1", substring = false); rule.tapText(" Rotate"); rule.tapText(" Flip")
    }

    @Test fun photoTextStickerAndSaveCopy() {
        openPhoto()
        rule.tab("Text")
        rule.tapText("Add text", false)
        rule.waitText("Drag the text on the photo", 10_000, substring = true)
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Hello ReelMint")
        rule.tab("Stickers")
        rule.tapText("🎉", false); rule.waitText("Drag the sticker", 10_000, substring = true)
        val before = TestUtil.countIn(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/PhotoVideoStudio")
        rule.onNodeWithContentDescription("Save copy").performClick()
        rule.waitText("Saved a copy", 60_000, substring = true)
        assertEquals(before + 1, TestUtil.countIn(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/PhotoVideoStudio"))
    }

    @Test fun photoPortraitBlurRunsWithoutCrash() {
        openPhoto()
        rule.tab("Portrait")
        rule.tapText("Blur", substring = false); Thread.sleep(6000); rule.waitForIdle()
        rule.onAllNodesWithText("Blur").onFirst().assertExists()
        rule.tapText("Remove", substring = false); Thread.sleep(3000); rule.waitForIdle()
        rule.waitText("transparent PNG", 10_000, substring = true)
    }

    // ---------- Collage ----------
    @Test fun collageBuildLayoutAndSave() {
        var incoming by mutableStateOf<Uri?>(null)
        rule.setContent { StudioTheme { CollageScreen(incoming, { incoming = null }, {}, {}) } }
        rule.waitText("Make a collage")
        TestUtil.photos.forEach { u -> incoming = u; rule.waitUntil(20_000) { incoming == null } }
        rule.waitText("Layout")
        rule.tapText("3 columns", false); rule.tapText("9:16", false); rule.tapText("Shuffle", false)
        val before = TestUtil.countIn(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/PhotoVideoStudio")
        rule.onNodeWithContentDescription("Save collage").performClick()
        rule.waitText("Saved to Pictures", 60_000, substring = true)
        assertEquals(before + 1, TestUtil.countIn(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/PhotoVideoStudio"))
    }

    // ---------- Slideshow ----------
    private fun slideshowWithPhotos(): SlideshowViewModel {
        val vm = SlideshowViewModel(app)
        rule.runOnUiThread { vm.addPhotos(TestUtil.photos) }
        rule.setContent { StudioTheme { SlideshowScreen(vm, {}, {}) } }
        rule.waitText("3 photos", 30_000, substring = true)
        return vm
    }

    @Test fun slideshowTabsTemplatesAndExport() {
        val vm = slideshowWithPhotos()
        rule.tab("Style"); rule.tapText("Birthday", substring = false)
        rule.tab("Titles"); rule.waitText("Happy Birthday!", 10_000)
        assertEquals(Motion.POP, vm.project.value.motion)
        rule.tab("Music"); rule.waitText("Choose a track", 10_000)
        rule.tab("Output"); rule.tapText("1:1", false); rule.waitText("High", 10_000)
        rule.runOnUiThread { vm.setCanvas(CanvasPreset.SQUARE); vm.setQuality(ExportQuality.SD) }
        rule.onNodeWithText("Export").performClick()
        rule.waitText("Saved to Movies", 150_000, substring = true)
        val t = TestUtil.measure(TestUtil.latestExport()!!)
        val (w, h) = CanvasPreset.SQUARE.size(ExportQuality.SD)
        assertEquals(w, t.w); assertEquals(h, t.h)
        assertTrue("expected ~7.5 s, got ${t.videoMs}", kotlin.math.abs(t.videoMs - 7500) < 700)
    }

    @Test fun slideshowReorderRemoveAndCaption() {
        val vm = slideshowWithPhotos()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Day one")
        assertEquals("Day one", vm.project.value.slideCaptions.values.first())
        val first = vm.project.value.photos.first()
        rule.onAllNodesWithContentDescription("Move later").onFirst().performClick()
        assertNotEquals(first, vm.project.value.photos.first())
        rule.onAllNodesWithContentDescription("Remove photo").onFirst().performClick()
        assertEquals(2, vm.project.value.photos.size)
    }

    @Test fun slideshowDraftSurvivesViewModelRecreation() {
        val vm = SlideshowViewModel(app)
        rule.runOnUiThread { vm.addPhotos(TestUtil.photos); vm.setTitle("Persist me") }
        rule.waitUntil(20_000) { vm.project.value.photos.size == 3 }
        Thread.sleep(1500)   // draft save is debounced
        val again = SlideshowViewModel(app)
        assertEquals(3, again.project.value.photos.size); assertEquals("Persist me", again.project.value.title)
    }

    // ---------- Video editor ----------
    private fun videoEditor(): VideoViewModel {
        val vm = VideoViewModel(app)
        rule.runOnUiThread { vm.startWith(listOf(TestUtil.video)) }
        rule.setContent { StudioTheme { VideoEditorScreen(vm, {}, {}) } }
        rule.waitText("1 clips", 30_000, substring = true)
        return vm
    }

    @Test fun videoSplitFreezeAndDelete() {
        val vm = videoEditor()
        rule.onNodeWithText(" Split").performScrollTo().performClick()
        rule.waitUntil(10_000) { vm.project.value.clips.size == 2 }
        rule.onAllNodesWithText("Freeze", substring = true).onFirst().performScrollTo().performClick()
        rule.waitUntil(20_000) { vm.project.value.clips.size == 4 }
        assertTrue(vm.project.value.clips.any { it.freeze })
        rule.onAllNodesWithContentDescription("Remove clip").onFirst().performScrollTo().performClick()
        assertEquals(3, vm.project.value.clips.size)
    }

    @Test fun videoSpeedLookCaptionStickerAndAudioControls() {
        val vm = videoEditor()
        rule.tapText("2.0×", false)
        assertEquals(2f, vm.project.value.clips[0].speed, 0f)
        rule.tapText("Hero time", false)
        assertEquals(SpeedRamp.HERO, vm.project.value.clips[0].ramp)
        rule.tab("Look"); rule.tapText("Cinematic", substring = false)
        assertEquals(PhotoFilter.SUNSET, vm.project.value.filter)
        rule.tab("Captions"); rule.tapText("Add text", false)
        rule.waitUntil(10_000) { vm.project.value.captions.size == 1 }
        rule.tapText("🔥", false); rule.waitUntil(10_000) { vm.project.value.stickers.size == 1 }
        rule.tapText("Neon", substring = false); assertEquals(CaptionStyle.NEON, vm.project.value.captionStyle)
        rule.tab("Audio")
        rule.onAllNodes(isToggleable()).onFirst().performClick()
        assertTrue(vm.project.value.muteOriginal)
        rule.onAllNodes(isToggleable()).onFirst().performClick()
        rule.tapText("Deep", substring = false); assertEquals(VoiceFx.DEEP, vm.project.value.originalVoice)
        rule.tab("Output"); rule.tapText("TikTok", substring = false)
        assertEquals(Platform.TIKTOK.canvas, vm.project.value.canvas)
    }

    @Test fun videoExportFromTheUiHasCorrectSizeAndAudio() {
        val vm = videoEditor()
        rule.tab("Output"); rule.tapText("Small")
        rule.runOnUiThread { vm.update { it.copy(canvas = CanvasPreset.PORTRAIT) } }
        assertEquals("quality before export", ExportQuality.SD, vm.project.value.quality)
        assertEquals("canvas before export", CanvasPreset.PORTRAIT, vm.project.value.canvas)
        rule.onNodeWithText("Export").performClick()
        rule.waitText("Saved to Movies", 150_000, substring = true)
        val t = TestUtil.measure(TestUtil.latestExport()!!)
        val (w, h) = CanvasPreset.PORTRAIT.size(ExportQuality.SD)
        assertEquals(w, t.w); assertEquals(h, t.h)
        assertTrue("has audio", t.audioMs > 0); assertTrue("audio vs video ${t.audioMs}/${t.videoMs}", kotlin.math.abs(t.audioMs - t.videoMs) < 400)
    }

    @Test fun videoTextToSpeechVoiceover() {
        val vm = VideoViewModel(app)
        rule.runOnUiThread { vm.startWith(listOf(TestUtil.video)); vm.generateVoice("Hello from ReelMint") }
        rule.waitUntil(40_000) { vm.project.value.voiceover != null }
        assertTrue(vm.project.value.voiceover!!.durationMs > 500)
    }
}
