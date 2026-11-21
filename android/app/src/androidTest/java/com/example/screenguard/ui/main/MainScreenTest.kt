package com.example.screenguard.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for ScreenGuard Compose rendering. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent {
      Text("ScreenGuard Protection")
    }
  }

  @Test
  fun testScreenGuardProtectionHeader_exists() {
    composeTestRule.onNodeWithText("ScreenGuard Protection").assertExists()
  }
}
