package com.livingbook.integration

import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import com.livingbook.LivingBookApp
import com.livingbook.MainActivity
import com.livingbook.session.SessionPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Launches the real app on Robolectric and walks the core loop: import a book, read a topic, move to the next one,
 * ask a question (demo mode, no network), and return to the book at the same place.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val samples = File("../samples").takeIf { it.isDirectory } ?: File("samples")

    @Test
    fun `import, read, navigate, ask and return`() {
        try {
            compose.waitUntilAtLeastOneExists(hasText("Import a book"), 10_000)
        } catch (e: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw e
        }

        val container = (compose.activity.application as LivingBookApp).container
        runBlocking { container.importer.import(Uri.fromFile(File(samples, "senses-primer.epub"))) }

        val title = "How Senses Become Perception: A Short Primer"
        compose.waitUntilAtLeastOneExists(hasText(title), 10_000)
        compose.onNodeWithText(title).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Topic 1 of", substring = true), 10_000)

        compose.onNodeWithContentDescription("Next topic").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Topic 2 of", substring = true), 10_000)
        val topicBefore = container.session.state.value.topicIndex

        compose.onNodeWithText("Ask").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Ask about this topic"), 10_000)
        assertEquals(SessionPhase.CONVERSATION, container.session.state.value.phase)

        compose.onNode(hasSetTextAction()).performTextInput("What are sensory receptors?")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Demo mode, not AI"), 15_000)

        compose.onNodeWithText("Back to the book").performClick()
        compose.waitUntil(10_000) { container.session.state.value.phase != SessionPhase.CONVERSATION }
        assertEquals(topicBefore, container.session.state.value.topicIndex)
        compose.onNodeWithText("Topic 2 of", substring = true).assertExists()

        // The table of contents jumps to any topic.
        compose.onNodeWithContentDescription("Contents and bookmarks").performClick()
        compose.waitUntilAtLeastOneExists(hasScrollToNodeAction(), 10_000)
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("Receptive fields"))
        compose.onAllNodes(hasText("Receptive fields") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { container.session.state.value.topic?.title == "Receptive fields" }
    }
}
