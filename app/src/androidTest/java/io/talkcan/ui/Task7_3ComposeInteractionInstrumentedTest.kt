package io.talkcan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.talkcan.model.*
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntimeSnapshot
import io.talkcan.profile.ProfileId
import io.talkcan.ui.theme.TalkcanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task7_3ComposeInteractionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createMockChannel(
        id: String,
        name: String,
        implementationId: String = "built-in:journal",
        available: Boolean = true,
        pendingCount: Int = 0,
        playbackPaused: Boolean = false
    ): ChannelRuntimeSnapshot {
        val prep = if (available) {
            ChannelPreparationAvailability.Available
        } else {
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.UnknownInstance)
        }
        return ChannelRuntimeSnapshot(
            id = id,
            name = name,
            implementationId = ChannelImplementationId(implementationId),
            enabled = true,
            preparation = prep,
            executionStatus = ChannelExecutionStatus.IDLE,
            pendingCount = pendingCount,
            playbackPaused = playbackPaused
        )
    }

    private fun createMockDescriptor(
        implementationId: String,
        summary: String = "Mock summary",
        unavailableMessage: String = "Mock unavailable message"
    ): ChannelImplementationDescriptor {
        val idObj = ChannelImplementationId(implementationId)
        return ChannelImplementationDescriptor(
            implementationId = idObj,
            presentation = ChannelPresentationMetadata(
                label = "Mock Label",
                summary = summary,
                unavailableMessage = unavailableMessage
            ),
            configuration = object : ChannelConfigurationProvider {
                override val implementationId = idObj
                override val currentSchemaVersion = 1
                override fun defaultPayload(): OpaqueJsonObject =
                    OpaqueJsonObject.parse("{}").getOrThrow()
                override fun validate(
                    schemaVersion: Int,
                    payload: OpaqueJsonObject
                ): ProviderConfigurationResult {
                    return ProviderConfigurationResult.Success(
                        ValidatedChannelConfiguration(idObj, schemaVersion, payload)
                    )
                }
                override fun migrateStep(
                    fromSchemaVersion: Int,
                    payload: OpaqueJsonObject
                ): ChannelConfigurationMigrationStep {
                    return ChannelConfigurationMigrationStep.Success(payload)
                }
            },
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false)
        )
    }

    class FakePttUiActions : PttUiActions {
        val calls = mutableListOf<String>()
        val activeChannels = mutableListOf<String>()
        val inputModes = mutableListOf<InputMode>()
        val configuredChannels = mutableListOf<String>()
        val pressedChannels = mutableListOf<String>()
        val releasedChannels = mutableListOf<String>()

        var onRsmClickCalled = false
        var onCarClickCalled = false
        var onChannelManagementClickCalled = false
        var onInstalledProvidersClickCalled = false
        var onProviderProfilesClickCalled = false
        var onVoiceProfilesClickCalled = false
        var onLogsClickCalled = false
        var onSystemReadinessClickCalled = false

        override fun requestPermissions() { calls.add("requestPermissions") }
        override fun requestManageExternalStorage() { calls.add("requestManageExternalStorage") }
        override fun pickDirectory(configurationOwnerId: String, fieldId: String) { calls.add("pickDirectory") }
        override fun pickMount(request: MountSelectionRequest) { calls.add("pickMount") }
        override fun openBluetoothSettings() { calls.add("openBluetoothSettings") }
        override fun scanForDevice() { calls.add("scanForDevice") }
        override fun pairTarget() { calls.add("pairTarget") }
        override fun refreshCarHfpConfiguration() { calls.add("refreshCarHfpConfiguration") }
        override fun selectCarHfpCandidate(selectionId: String) { calls.add("selectCarHfpCandidate") }
        override fun connectSerial() { calls.add("connectSerial") }
        override fun retry() { calls.add("retry") }
        override fun disconnectSerial() { calls.add("disconnectSerial") }
        override fun setActiveChannel(id: String) {
            calls.add("setActiveChannel($id)")
            activeChannels.add(id)
        }
        override fun setInputMode(mode: InputMode) {
            calls.add("setInputMode($mode)")
            inputModes.add(mode)
        }
        override fun navigateToRadio() { calls.add("navigateToRadio") }
        override fun navigateToSettingsHome() { calls.add("navigateToSettingsHome") }
        override fun navigateToRsmSetup() { calls.add("navigateToRsmSetup"); onRsmClickCalled = true }
        override fun navigateToCarSetup() { calls.add("navigateToCarSetup"); onCarClickCalled = true }
        override fun navigateToChannelConfiguration(channelId: String) {
            calls.add("navigateToChannelConfiguration($channelId)")
            configuredChannels.add(channelId)
        }
        override fun navigateToChannelManagement() { calls.add("navigateToChannelManagement"); onChannelManagementClickCalled = true }
        override fun navigateToChannelCreation(implementationId: ChannelImplementationId, displayName: String) { calls.add("navigateToChannelCreation") }
        override fun navigateBack() { calls.add("navigateBack") }
        override fun navigateToLogAnalysis() { calls.add("navigateToLogAnalysis"); onLogsClickCalled = true }
        override fun phonePttPressed(channelId: String) {
            calls.add("phonePttPressed($channelId)")
            pressedChannels.add(channelId)
        }
        override fun phonePttReleased(channelId: String) {
            calls.add("phonePttReleased($channelId)")
            releasedChannels.add(channelId)
        }
        override fun createChannel(
            implementationId: ChannelImplementationId,
            displayName: String,
            payload: OpaqueJsonObject
        ): String? {
            calls.add("createChannel")
            return null
        }
        override fun updateChannelConfiguration(channelId: String, payload: OpaqueJsonObject): String? {
            calls.add("updateChannelConfiguration")
            return null
        }
        override fun removeChannel(id: String) { calls.add("removeChannel") }
        override fun moveChannel(id: String, toIndex: Int) { calls.add("moveChannel") }
        override fun renameChannel(id: String, newName: String) { calls.add("renameChannel") }
        override fun navigateToPackageManagement() { calls.add("navigateToPackageManagement"); onInstalledProvidersClickCalled = true }
        override fun resolvePackageRepository(url: String) { calls.add("resolvePackageRepository") }
        override fun selectPackageRelease(releaseId: String) { calls.add("selectPackageRelease") }
        override fun confirmPackageInstall(acknowledged: Boolean) { calls.add("confirmPackageInstall") }
        override fun rollbackPackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity) { calls.add("rollbackPackage") }
        override fun removePackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity) { calls.add("removePackage") }
        override fun cancelPackageInspection() { calls.add("cancelPackageInspection") }
        override fun refreshPackageManagement(url: String) { calls.add("refreshPackageManagement") }
        override fun navigateToGenericProfiles() { calls.add("navigateToGenericProfiles"); onProviderProfilesClickCalled = true }
        override fun createGenericProfile(
            identity: io.talkcan.profile.ProfileTypeIdentity,
            displayName: String,
            scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
            secrets: Map<String, CharSequence>
        ) { calls.add("createGenericProfile") }
        override fun updateGenericProfile(
            profileId: ProfileId,
            displayName: String,
            scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
            secretEdits: Map<String, io.talkcan.profile.SecretEditAction>
        ) { calls.add("updateGenericProfile") }
        override fun deleteGenericProfile(profileId: ProfileId) { calls.add("deleteGenericProfile") }
        override fun navigateToVoiceProfiles() { calls.add("navigateToVoiceProfiles"); onVoiceProfilesClickCalled = true }
        override fun navigateToSystemReadiness() { calls.add("navigateToSystemReadiness"); onSystemReadinessClickCalled = true }
    }

    @Test
    fun channelPrimaryClickCallsSetActiveChannelAndDoesNotStartPtt() {
        val channel1 = createMockChannel(id = "ch-1", name = "Channel One", pendingCount = 0)
        val channel2 = createMockChannel(id = "ch-2", name = "Channel Two", pendingCount = 5)
        val channels = listOf(channel1, channel2)
        val descriptors = listOf(createMockDescriptor("built-in:journal"))
        val actions = FakePttUiActions()

        val appState = AppState(
            channels = channels,
            activeChannelId = "ch-1",
            inputModeAvailability = InputModeAvailability(work = true, onTheRoad = true, onAPinch = true),
            inputMode = InputMode.OnAPinch
        )

        composeRule.setContent {
            TalkcanTheme {
                MainDashboardScreen(
                    appState = appState,
                    level = 0.0f,
                    isCapturing = false,
                    providerDescriptors = descriptors,
                    actions = actions
                )
            }
        }

        // Tap on channel 2 primary selection area
        composeRule.onNodeWithTag("channel-primary-ch-2").performScrollTo().performClick()

        // Assert: setActiveChannel called on channel 2
        assertTrue(actions.activeChannels.contains("ch-2"))
        // Assert: PTT pressed/released is empty
        assertTrue(actions.pressedChannels.isEmpty())
        assertTrue(actions.releasedChannels.isEmpty())

        // Verify independent components: Settings and Pending badge
        // 1. Settings button
        composeRule.onNodeWithTag("channel-settings-ch-2").performScrollTo().performClick()
        assertTrue(actions.configuredChannels.contains("ch-2"))

        // 2. Pending badge
        composeRule.onNodeWithTag("channel-pending-ch-2").performScrollTo().performClick()
        // verify active channels contains ch-2 again (was clicked once as primary, now again via badge)
        assertEquals(2, actions.activeChannels.filter { it == "ch-2" }.size)
    }

    @Test
    fun phonePttPointerUpReleasesThePressedChannel() {
        val actions = FakePttUiActions()
        val channel = createMockChannel(id = "ch-1", name = "Channel One")
        val appState = AppState(
            channels = listOf(channel),
            activeChannelId = channel.id,
            inputModeAvailability = InputModeAvailability(
                work = true,
                onTheRoad = true,
                onAPinch = true,
            ),
            inputMode = InputMode.OnAPinch,
        )

        composeRule.setContent {
            TalkcanTheme {
                MainDashboardScreen(
                    appState = appState,
                    level = 0.0f,
                    isCapturing = false,
                    providerDescriptors = listOf(createMockDescriptor("built-in:journal")),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithTag("phone-ptt-dock").performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(channel.id), actions.pressedChannels)
        assertEquals(listOf(channel.id), actions.releasedChannels)
    }

    @Test
    fun audioDeviceTilesClickSelectionAndSetupConstraints() {
        val channels = listOf(createMockChannel(id = "ch-1", name = "Channel One"))
        val descriptors = listOf(createMockDescriptor("built-in:journal"))
        val actions = FakePttUiActions()

        // Work: available (true), OnTheRoad: unavailable (false), OnAPinch: available (true)
        val appState = AppState(
            channels = channels,
            activeChannelId = "ch-1",
            inputModeAvailability = InputModeAvailability(work = true, onTheRoad = false, onAPinch = true),
            inputMode = InputMode.OnAPinch
        )

        composeRule.setContent {
            TalkcanTheme {
                MainDashboardScreen(
                    appState = appState,
                    level = 0.0f,
                    isCapturing = false,
                    providerDescriptors = descriptors,
                    actions = actions
                )
            }
        }

        // Click on available device tile (Work/Radio)
        composeRule.onNodeWithTag("device-tile-Work").performClick()
        assertTrue(actions.inputModes.contains(InputMode.Work))

        // Click on unavailable device tile (OnTheRoad/Car) -> should not select
        composeRule.onNodeWithTag("device-tile-OnTheRoad").performClick()
        assertFalse(actions.inputModes.contains(InputMode.OnTheRoad))
        // And should not trigger setup navigation
        assertFalse(actions.calls.contains("navigateToCarSetup"))

        // Long-click on available tile (Work) -> should not trigger setup navigation
        composeRule.onNodeWithTag("device-tile-Work").performTouchInput {
            longClick()
        }
        assertFalse(actions.calls.contains("navigateToRsmSetup"))

        // Long-click on unavailable tile (OnTheRoad) -> should not trigger setup navigation
        composeRule.onNodeWithTag("device-tile-OnTheRoad").performTouchInput {
            longClick()
        }
        assertFalse(actions.calls.contains("navigateToCarSetup"))
    }

    @Test
    fun settingsHomeScreenDisplaysAllDestinationsAndTriggersCallbacks() {
        val actions = FakePttUiActions()

        composeRule.setContent {
            TalkcanTheme {
                SettingsHomeScreen(
                    onRsmClick = { actions.navigateToRsmSetup() },
                    onCarClick = { actions.navigateToCarSetup() },
                    onChannelManagementClick = { actions.navigateToChannelManagement() },
                    onInstalledProvidersClick = { actions.navigateToPackageManagement() },
                    onProviderProfilesClick = { actions.navigateToGenericProfiles() },
                    onVoiceProfilesClick = { actions.navigateToVoiceProfiles() },
                    onLogsClick = { actions.navigateToLogAnalysis() },
                    onSystemReadinessClick = { actions.navigateToSystemReadiness() },
                    permissionsReady = true,
                    modelsReady = true,
                    voiceReady = true,
                    storageReady = true
                )
            }
        }

        // 1. Verify all settings rows are present
        val expectedRows = listOf(
            "Radio",
            "Car",
            "Phone",
            "Channel management",
            "Installed providers",
            "Provider profiles",
            "Voice profiles",
            "Permissions",
            "Models",
            "Offline voice",
            "Storage",
            "Diagnostic logs"
        )
        for (row in expectedRows) {
            composeRule.onNodeWithTag("settings-row-$row")
                .performScrollTo()
                .assertIsDisplayed()
        }

        // 2. Phone row should be disabled (not enabled/clickable)
        composeRule.onNodeWithTag("settings-row-Phone").assertIsNotEnabled()

        // 3. Click and verify enabled callbacks
        composeRule.onNodeWithTag("settings-row-Radio").performScrollTo().performClick()
        assertTrue(actions.onRsmClickCalled)

        composeRule.onNodeWithTag("settings-row-Car").performScrollTo().performClick()
        assertTrue(actions.onCarClickCalled)

        composeRule.onNodeWithTag("settings-row-Channel management")
            .performScrollTo()
            .performClick()
        assertTrue(actions.onChannelManagementClickCalled)

        composeRule.onNodeWithTag("settings-row-Installed providers")
            .performScrollTo()
            .performClick()
        assertTrue(actions.onInstalledProvidersClickCalled)

        composeRule.onNodeWithTag("settings-row-Provider profiles")
            .performScrollTo()
            .performClick()
        assertTrue(actions.onProviderProfilesClickCalled)

        composeRule.onNodeWithTag("settings-row-Voice profiles")
            .performScrollTo()
            .performClick()
        assertTrue(actions.onVoiceProfilesClickCalled)

        composeRule.onNodeWithTag("settings-row-Permissions").performScrollTo().performClick()
        assertTrue(actions.onSystemReadinessClickCalled)

        // Reset readiness check callback flag to test subsequent readiness rows
        actions.onSystemReadinessClickCalled = false

        composeRule.onNodeWithTag("settings-row-Models").performScrollTo().performClick()
        assertTrue(actions.onSystemReadinessClickCalled)
        actions.onSystemReadinessClickCalled = false

        composeRule.onNodeWithTag("settings-row-Offline voice")
            .performScrollTo()
            .performClick()
        assertTrue(actions.onSystemReadinessClickCalled)
        actions.onSystemReadinessClickCalled = false

        composeRule.onNodeWithTag("settings-row-Storage").performScrollTo().performClick()
        assertTrue(actions.onSystemReadinessClickCalled)

        composeRule.onNodeWithTag("settings-row-Diagnostic logs")
            .performScrollTo()
            .performClick()
        assertTrue(actions.onLogsClickCalled)
    }

    @Test
    fun fixedPhonePttDockBoundsRemainStableWhileContentScrollsAndStateChanges() {
        // Create 15 channels to guarantee dashboard is scrollable
        val channels = (1..15).map {
            createMockChannel(id = "ch-$it", name = "Channel Number $it")
        }
        val descriptors = listOf(createMockDescriptor("built-in:journal"))
        val actions = FakePttUiActions()
        var isCapturing by androidx.compose.runtime.mutableStateOf(false)

        var appState by androidx.compose.runtime.mutableStateOf(
            AppState(
                channels = channels,
                activeChannelId = "ch-1",
                inputModeAvailability = InputModeAvailability(work = true, onTheRoad = true, onAPinch = true),
                inputMode = InputMode.OnAPinch,
                pttAudioState = PttAudioOperationState()
            )
        )

        composeRule.setContent {
            TalkcanTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainDashboardScreen(
                        appState = appState,
                        level = 0.0f,
                        isCapturing = isCapturing,
                        providerDescriptors = descriptors,
                        actions = actions,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithText("Audio device").assertIsDisplayed()
        composeRule.onNodeWithText("Talk level").assertDoesNotExist()

        // Measure initial dock position and bounds
        val initialBounds = composeRule.onNodeWithTag("phone-ptt-dock").getUnclippedBoundsInRoot()
        val initialPanelBounds = composeRule
            .onNodeWithTag("dashboard-operational-panel")
            .getUnclippedBoundsInRoot()
        val initialRouteButtonBounds = composeRule
            .onNodeWithTag("device-tile-OnAPinch")
            .getUnclippedBoundsInRoot()

        // 1. Scroll the dashboard
        composeRule.onNode(hasScrollAction()).performTouchInput {
            swipeUp()
        }
        composeRule.waitForIdle()

        val boundsAfterScroll = composeRule.onNodeWithTag("phone-ptt-dock").getUnclippedBoundsInRoot()
        assertBoundsEqual(initialBounds, boundsAfterScroll)
        val panelBoundsAfterScroll = composeRule
            .onNodeWithTag("dashboard-operational-panel")
            .getUnclippedBoundsInRoot()
        assertBoundsEqual(initialPanelBounds, panelBoundsAfterScroll)

        // 2. Perform selection update (activeChannelId change)
        appState = appState.copy(activeChannelId = "ch-2")
        composeRule.waitForIdle()

        val boundsAfterSelection = composeRule.onNodeWithTag("phone-ptt-dock").getUnclippedBoundsInRoot()
        assertBoundsEqual(initialBounds, boundsAfterSelection)
        val panelBoundsAfterSelection = composeRule
            .onNodeWithTag("dashboard-operational-panel")
            .getUnclippedBoundsInRoot()
        assertBoundsEqual(initialPanelBounds, panelBoundsAfterSelection)

        // 3. Perform state update (pttAudioState change to recording)
        isCapturing = true
        appState = appState.copy(
            pttAudioState = PttAudioOperationState(
                source = PttSource.Phone,
                channelId = "ch-2",
                mode = InputMode.OnAPinch,
                phase = PttAudioOperationPhase.RECORDING
            )
        )
        composeRule.waitForIdle()

        // Verify the dock text actually updated
        composeRule.onNodeWithText("Release to send").assertIsDisplayed()
        composeRule.onNodeWithText("Talk level").assertIsDisplayed()
        composeRule.onNodeWithText("Audio device").assertDoesNotExist()
        val talkLevelBounds = composeRule
            .onNodeWithTag("talk-level-card")
            .getUnclippedBoundsInRoot()
        assertEquals(
            "Talk-level card must match route-button height",
            initialRouteButtonBounds.bottom.value - initialRouteButtonBounds.top.value,
            talkLevelBounds.bottom.value - talkLevelBounds.top.value,
            0.1f,
        )

        val boundsAfterStateUpdate = composeRule.onNodeWithTag("phone-ptt-dock").getUnclippedBoundsInRoot()
        assertBoundsEqual(initialBounds, boundsAfterStateUpdate)
        val panelBoundsAfterStateUpdate = composeRule
            .onNodeWithTag("dashboard-operational-panel")
            .getUnclippedBoundsInRoot()
        assertBoundsEqual(initialPanelBounds, panelBoundsAfterStateUpdate)
    }

    private fun assertBoundsEqual(expected: DpRect, actual: DpRect) {
        assertEquals("Left bound mismatch", expected.left.value, actual.left.value, 0.1f)
        assertEquals("Right bound mismatch", expected.right.value, actual.right.value, 0.1f)
        assertEquals("Top bound mismatch", expected.top.value, actual.top.value, 0.1f)
        assertEquals("Bottom bound mismatch", expected.bottom.value, actual.bottom.value, 0.1f)
    }
}
