package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.ui.screens.ModeSelectionScreen
import com.example.ui.theme.HomeVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeVaultUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `mode selection renders both client and server cards and responds to taps`() {
        var clientSelected = false
        var serverSelected = false

        composeTestRule.setContent {
            HomeVaultTheme {
                ModeSelectionScreen(
                    onSelectClient = { clientSelected = true },
                    onSelectServer = { serverSelected = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("select_client_mode_card")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(clientSelected)

        composeTestRule.onNodeWithTag("select_server_mode_card")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(serverSelected)
    }
}
