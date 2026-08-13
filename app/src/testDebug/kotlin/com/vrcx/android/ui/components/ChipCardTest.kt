package com.vrcx.android.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vrcx.android.ui.theme.VrcxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChipCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `labels are rendered as given`() {
        compose.setContent {
            VrcxTheme { ChipCard(title = "Platforms", labels = listOf("PC", "Quest (Good)")) }
        }

        compose.onNodeWithText("Platforms").assertIsDisplayed()
        compose.onNodeWithText("PC").assertIsDisplayed()
        // The caller formats the suffix in, so the component never has to know
        // that avatar platforms carry a performance rating and world ones do not.
        compose.onNodeWithText("Quest (Good)").assertIsDisplayed()
    }

    @Test
    fun `an empty card is no card, so callers need no guard`() {
        compose.setContent {
            VrcxTheme { ChipCard(title = "Tags", labels = emptyList()) }
        }

        compose.onNodeWithText("Tags").assertDoesNotExist()
    }
}
