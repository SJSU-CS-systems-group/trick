package net.discdd.trick

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ComposeButtonUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun buttonClick_changesText() {
        composeTestRule.setContent {
            App()
        }

        composeTestRule.onNodeWithTag("greetingSection").assertDoesNotExist()

        composeTestRule.onNodeWithText("Click me!").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("greetingSection").assertIsDisplayed()

        // Test that greeting content appears
        composeTestRule.onNodeWithText("Compose:", substring = true).assertIsDisplayed()
    }

    @Test
    fun buttonClick_showsGreetingContent() {
        composeTestRule.setContent {
            App()
        }

        // Click button to show content
        composeTestRule.onNodeWithText("Click me!").performClick()
        composeTestRule.waitForIdle()

        // Verify greeting section and content
        composeTestRule.onNodeWithTag("greetingSection").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello", substring = true, ignoreCase = true).assertIsDisplayed()
    }
}