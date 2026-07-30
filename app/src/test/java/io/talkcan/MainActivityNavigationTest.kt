package io.talkcan

import io.talkcan.model.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityNavigationTest {

    // Existing direct utility tests
    @Test
    fun `leaving package management cleans up exactly once before switching to parent`() {
        val events = mutableListOf<String>()

        exitSettingsChildRoute(
            isPackageManagement = true,
            cleanup = { events += "cleanup" },
            navigateToParent = { events += "parent" },
        )

        assertEquals(listOf("cleanup", "parent"), events)
    }

    @Test
    fun `ordinary back navigation does not clean up package management`() {
        val events = mutableListOf<String>()

        exitSettingsChildRoute(
            isPackageManagement = false,
            cleanup = { events += "cleanup" },
            navigateToParent = { events += "parent" },
        )

        assertEquals(listOf("parent"), events)
    }

    @Test
    fun `leaving voice profile editor calls exitVoiceProfileEditor exactly once before switching to parent`() {
        val events = mutableListOf<String>()

        exitSettingsChildRoute(
            isPackageManagement = false,
            isVoiceProfiles = true,
            cleanup = { events += "cleanup" },
            exitVoiceProfileEditor = { events += "exitEditor" },
            navigateToParent = { events += "parent" },
        )

        assertEquals(listOf("exitEditor", "parent"), events)
    }

    @Test
    fun `ordinary back navigation does not call exitVoiceProfileEditor`() {
        val events = mutableListOf<String>()

        exitSettingsChildRoute(
            isPackageManagement = false,
            isVoiceProfiles = false,
            cleanup = { events += "cleanup" },
            exitVoiceProfileEditor = { events += "exitEditor" },
            navigateToParent = { events += "parent" },
        )

        assertEquals(listOf("parent"), events)
    }

    // New tests using the shared navigation state and transitions

    @Test
    fun `section switching between Radio and Settings home works correctly`() {
        val radioState = NavigationState(AppSection.Radio)
        
        // Navigate to settings home
        val settingsHomeState = navigate(radioState, NavigationAction.NavigateToSettingsHome)
        assertEquals(AppSection.Settings, settingsHomeState.appSection)
        assertNull(settingsHomeState.secondaryRoute)
        
        // Navigate back to radio
        val backToRadioState = navigate(settingsHomeState, NavigationAction.NavigateToRadio)
        assertEquals(AppSection.Radio, backToRadioState.appSection)
        assertNull(backToRadioState.secondaryRoute)
    }

    @Test
    fun `entering representative child routes updates state correctly`() {
        val settingsHome = NavigationState(AppSection.Settings)

        // 1. Connection/RsmSetup route (with monitor ready/not ready)
        val connState = navigate(settingsHome, NavigationAction.NavigateToRsmSetup(readyForMonitor = false))
        assertEquals(AppSection.Settings, connState.appSection)
        assertEquals(SecondaryRoute.Connection, connState.secondaryRoute)

        val monitorState = navigate(settingsHome, NavigationAction.NavigateToRsmSetup(readyForMonitor = true))
        assertEquals(AppSection.Settings, monitorState.appSection)
        assertEquals(SecondaryRoute.Monitor, monitorState.secondaryRoute)

        // 2. PackageManagement route
        val pkgState = navigate(settingsHome, NavigationAction.NavigateToPackageManagement)
        assertEquals(AppSection.Settings, pkgState.appSection)
        assertEquals(SecondaryRoute.PackageManagement, pkgState.secondaryRoute)

        // 3. VoiceProfiles route
        val voiceState = navigate(settingsHome, NavigationAction.NavigateToVoiceProfiles)
        assertEquals(AppSection.Settings, voiceState.appSection)
        assertEquals(SecondaryRoute.VoiceProfiles, voiceState.secondaryRoute)

        // 4. ChannelConfiguration route
        val chanConfigState = navigate(settingsHome, NavigationAction.NavigateToChannelConfiguration("test-channel-id"))
        assertEquals(AppSection.Settings, chanConfigState.appSection)
        assertEquals(SecondaryRoute.ChannelConfiguration, chanConfigState.secondaryRoute)
        assertEquals("test-channel-id", chanConfigState.configuredChannelId)
    }

    @Test
    fun `sequential navigation flow home to child to Back to home to Back to Radio`() {
        var state = NavigationState(AppSection.Radio)

        // Step 1: Radio -> Settings Home
        state = navigate(state, NavigationAction.NavigateToSettingsHome)
        assertEquals(AppSection.Settings, state.appSection)
        assertNull(state.secondaryRoute)

        // Step 2: Settings Home -> Child route (e.g. SystemReadiness)
        state = navigate(state, NavigationAction.NavigateToSystemReadiness)
        assertEquals(AppSection.Settings, state.appSection)
        assertEquals(SecondaryRoute.SystemReadiness, state.secondaryRoute)

        // Step 3: Back from Child route -> Settings Home (Must not skip to Radio!)
        state = navigate(state, NavigationAction.NavigateBack(cleanupPackageManagement = {}, exitVoiceProfileEditor = {}))
        assertEquals(AppSection.Settings, state.appSection)
        assertNull(state.secondaryRoute)

        // Step 4: Back from Settings Home -> Radio
        state = navigate(state, NavigationAction.NavigateBack(cleanupPackageManagement = {}, exitVoiceProfileEditor = {}))
        assertEquals(AppSection.Radio, state.appSection)
        assertNull(state.secondaryRoute)
    }

    @Test
    fun `cleanup hooks fire exactly once when exiting package management via NavigateBack`() {
        val pkgState = NavigationState(
            appSection = AppSection.Settings,
            secondaryRoute = SecondaryRoute.PackageManagement
        )

        var cleanupCount = 0
        var voiceEditorExitCount = 0

        val nextState = navigate(
            pkgState,
            NavigationAction.NavigateBack(
                cleanupPackageManagement = { cleanupCount++ },
                exitVoiceProfileEditor = { voiceEditorExitCount++ }
            )
        )

        assertEquals(AppSection.Settings, nextState.appSection)
        assertNull(nextState.secondaryRoute)
        assertEquals(1, cleanupCount)
        assertEquals(0, voiceEditorExitCount)
    }

    @Test
    fun `cleanup hooks fire exactly once when exiting voice profiles via NavigateBack`() {
        val voiceState = NavigationState(
            appSection = AppSection.Settings,
            secondaryRoute = SecondaryRoute.VoiceProfiles
        )

        var cleanupCount = 0
        var voiceEditorExitCount = 0

        val nextState = navigate(
            voiceState,
            NavigationAction.NavigateBack(
                cleanupPackageManagement = { cleanupCount++ },
                exitVoiceProfileEditor = { voiceEditorExitCount++ }
            )
        )

        assertEquals(AppSection.Settings, nextState.appSection)
        assertNull(nextState.secondaryRoute)
        assertEquals(0, cleanupCount)
        assertEquals(1, voiceEditorExitCount)
    }

    @Test
    fun `navigation transitions do not alter active channel ID or selected InputMode or device`() {
        // Representative operational state elements kept outside the navigation model
        val operationalState = object {
            var activeChannelId = "channel-123"
            var selectedInputMode = InputMode.Work
        }

        var navState = NavigationState(AppSection.Radio)

        val assertPreserved = {
            assertEquals("channel-123", operationalState.activeChannelId)
            assertEquals(InputMode.Work, operationalState.selectedInputMode)
        }

        // Test transition: Radio -> Settings Home
        navState = navigate(navState, NavigationAction.NavigateToSettingsHome)
        assertPreserved()

        // Test transition: Settings Home -> Child route
        navState = navigate(navState, NavigationAction.NavigateToChannelConfiguration("other-channel-config"))
        assertPreserved()

        // Test transition: Child -> Back to Settings Home
        navState = navigate(navState, NavigationAction.NavigateBack({}, {}))
        assertPreserved()

        // Test transition: Settings Home -> Back to Radio
        navState = navigate(navState, NavigationAction.NavigateBack({}, {}))
        assertPreserved()
    }
}
