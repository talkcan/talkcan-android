package io.talkcan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.talkcan.model.OfflineNavigationVoiceIssue
import org.junit.Rule
import org.junit.Test

class InitialSetupScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun verifiedVoiceShowsCompletedStatusWithoutVoiceSetupAction() {
        renderSetup(
            missingPermissions = emptyList(),
            needsManageExternalStorage = false,
            invalidModelSets = emptyList(),
            offlineNavigationVoiceIssue = null,
            voiceSetupRequiresManualNavigation = false,
        )

        composeRule.onNodeWithText("✓ Offline voice verified").assertIsDisplayed()
        composeRule.onNodeWithText("Install offline voice").assertDoesNotExist()
        composeRule.onNodeWithText("Retry offline voice setup").assertDoesNotExist()
    }

    @Test
    fun failedVoiceProbeShowsDiagnosticAndEnabledRetry() {
        renderSetup(
            missingPermissions = emptyList(),
            needsManageExternalStorage = false,
            invalidModelSets = emptyList(),
            offlineNavigationVoiceIssue = OfflineNavigationVoiceIssue(
                diagnostic = "The selected voice did not produce audio.",
                enginePackage = "io.talkcan.test.tts",
            ),
            voiceSetupRequiresManualNavigation = false,
        )

        composeRule.onNodeWithText("Error: Offline voice verification failed").assertIsDisplayed()
        composeRule.onNodeWithText("The selected voice did not produce audio.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry offline voice setup").assertIsEnabled()
    }


    @Test
    fun settingsFallbackShowsManualVoiceInstallationGuidance() {
        renderSetup(
            missingPermissions = emptyList(),
            needsManageExternalStorage = false,
            invalidModelSets = emptyList(),
            offlineNavigationVoiceIssue = OfflineNavigationVoiceIssue(
                diagnostic = "No TTS data installer is available.",
                enginePackage = null,
            ),
            voiceSetupRequiresManualNavigation = true,
        )

        composeRule
            .onNodeWithText(
                "In Android Settings, open Text-to-speech output and install an offline English voice.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun scopedVoiceInstallerDoesNotShowSettingsFallbackGuidance() {
        renderSetup(
            missingPermissions = emptyList(),
            needsManageExternalStorage = false,
            invalidModelSets = emptyList(),
            offlineNavigationVoiceIssue = OfflineNavigationVoiceIssue(
                diagnostic = "Offline English voice data is missing.",
                enginePackage = "io.talkcan.test.tts",
            ),
            voiceSetupRequiresManualNavigation = false,
        )

        composeRule
            .onNodeWithText(
                "In Android Settings, open Text-to-speech output and install an offline English voice.",
            )
            .assertDoesNotExist()
        composeRule.onNodeWithText("Retry offline voice setup").assertIsEnabled()
    }

    @Test
    fun voiceActionWaitsDisabledUntilEarlierPrerequisitesComplete() {
        renderSetup(
            missingPermissions = listOf("android.permission.RECORD_AUDIO"),
            needsManageExternalStorage = false,
            invalidModelSets = emptyList(),
            offlineNavigationVoiceIssue = OfflineNavigationVoiceIssue(
                diagnostic = "Offline English voice data is missing.",
                enginePackage = "io.talkcan.test.tts",
            ),
            voiceSetupRequiresManualNavigation = false,
        )

        composeRule.onNodeWithText("Waiting for earlier setup steps to complete…").assertIsDisplayed()
        composeRule.onNodeWithText("Retry offline voice setup").assertIsNotEnabled()
    }

    private fun renderSetup(
        missingPermissions: List<String>,
        needsManageExternalStorage: Boolean,
        invalidModelSets: List<String>,
        offlineNavigationVoiceIssue: OfflineNavigationVoiceIssue?,
        voiceSetupRequiresManualNavigation: Boolean,
    ) {
        composeRule.setContent {
            MaterialTheme {
                InitialSetupScreen(
                    missingPermissions = missingPermissions,
                    needsManageExternalStorage = needsManageExternalStorage,
                    invalidModelSets = invalidModelSets,
                    error = null,
                    offlineNavigationVoiceIssue = offlineNavigationVoiceIssue,
                    voiceSetupRequiresManualNavigation = voiceSetupRequiresManualNavigation,
                    onGrantPermissions = {},
                    onGrantManageExternalStorage = {},
                    onStartModelDownload = {},
                    onResolveVoiceSetup = {},
                    modifier = Modifier,
                )
            }
        }
    }
}
