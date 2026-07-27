package io.talkcan

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityNavigationTest {
    @Test
    fun `leaving package management cleans up exactly once before switching to main`() {
        val events = mutableListOf<String>()

        exitDashboardRoute(
            isPackageManagement = true,
            cleanup = { events += "cleanup" },
            setMainRoute = { events += "main" },
        )

        assertEquals(listOf("cleanup", "main"), events)
    }

    @Test
    fun `ordinary back navigation does not clean up package management`() {
        val events = mutableListOf<String>()

        exitDashboardRoute(
            isPackageManagement = false,
            cleanup = { events += "cleanup" },
            setMainRoute = { events += "main" },
        )

        assertEquals(listOf("main"), events)
    }

    @Test
    fun `leaving voice profile editor calls exitVoiceProfileEditor exactly once before switching to main`() {
        val events = mutableListOf<String>()

        exitDashboardRoute(
            isPackageManagement = false,
            isVoiceProfiles = true,
            cleanup = { events += "cleanup" },
            exitVoiceProfileEditor = { events += "exitEditor" },
            setMainRoute = { events += "main" },
        )

        assertEquals(listOf("exitEditor", "main"), events)
    }

    @Test
    fun `ordinary back navigation does not call exitVoiceProfileEditor`() {
        val events = mutableListOf<String>()

        exitDashboardRoute(
            isPackageManagement = false,
            isVoiceProfiles = false,
            cleanup = { events += "cleanup" },
            exitVoiceProfileEditor = { events += "exitEditor" },
            setMainRoute = { events += "main" },
        )

        assertEquals(listOf("main"), events)
    }
}
