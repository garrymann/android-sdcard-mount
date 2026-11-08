package dev.rsm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.rsm.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The screen renders one of exactly two states and never a third.
     *
     * Asserting "list or empty state" rather than one specific outcome is deliberate: whether any
     * volume is present depends on the hardware the suite happens to run on, and an emulator with no
     * removable media is a legitimate configuration. What must hold everywhere is that the app gets
     * as far as a rendered volume surface — which is also FR-109's obligation.
     *
     * The title assertion moved from `app_name` to `volumes_title` when the screen gained a real top
     * app bar: the app name now lives on the About screen, and the bar reads "Storage".
     */
    @Test
    fun launch_rendersAVolumeSurface() {
        composeRule.onNodeWithText(context.getString(R.string.volumes_title)).assertIsDisplayed()

        val listNodes = composeRule.onAllNodesWithTagCount("volume-list")
        val emptyNodes = composeRule.onAllNodesWithTagCount("startup-screen")
        assertTrue(
            "expected either the volume list or the empty state to be present",
            listNodes + emptyNodes > 0,
        )
    }

    /** The empty state must explain what to do, never show a bare failure (FR-602). */
    @Test
    fun emptyState_whenShown_explainsWhatToDo() {
        if (composeRule.onAllNodesWithTagCount("startup-screen") > 0) {
            composeRule.onNodeWithTag("startup-screen").assertIsDisplayed()
            composeRule
                .onNodeWithText(context.getString(R.string.startup_message))
                .assertIsDisplayed()
        }
    }

    /**
     * The overflow menu reaches About, and About shows a version.
     *
     * The version is asserted by tag rather than by value: it is derived from git at build time (see
     * the versioning block in app/build.gradle.kts), so any literal here would be wrong by the next
     * commit. What matters is that the line renders and is non-empty.
     */
    @Test
    fun overflowMenu_reachesAbout_whichShowsAVersion() {
        composeRule.onNodeWithTag("overflow").performClick()
        composeRule.onNodeWithTag("menu-about").performClick()

        composeRule.onNodeWithTag("about-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("about-version").assertIsDisplayed()
        composeRule.onNodeWithTag("about-github").assertIsDisplayed()
        composeRule.onNodeWithTag("about-donate").assertIsDisplayed()
    }

    /**
     * Content starts below the status bar.
     *
     * This is the regression guard for the edge-to-edge bug: at targetSdk 37 the platform draws the
     * app behind the system bars, and the previous screen applied a flat 16dp padding, so its first
     * row sat under the notification bar. The top app bar's top edge must therefore be at or below
     * the top inset, never above it.
     */
    @Test
    fun topBar_isNotUnderTheStatusBar() {
        // Platform WindowInsets rather than WindowInsetsCompat: Type.statusBars() is API 30 and
        // minSdk is 30, so the compat wrapper would only add a dependency question to a test that
        // does not need one. Read on the main thread — decorView is not safe to touch off it.
        var statusBarTop = 0
        composeRule.activityRule.scenario.onActivity { activity ->
            statusBarTop = activity.window.decorView.rootWindowInsets
                ?.getInsets(android.view.WindowInsets.Type.statusBars())
                ?.top
                ?: 0
        }

        val titleTop = composeRule
            .onNodeWithText(context.getString(R.string.volumes_title))
            .fetchSemanticsNode()
            .boundsInWindow
            .top

        assertTrue(
            "top bar title at $titleTop overlaps the $statusBarTop px status bar inset",
            titleTop >= statusBarTop,
        )
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(
    tag: String,
): Int = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
