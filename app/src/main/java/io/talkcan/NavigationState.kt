package io.talkcan

enum class AppSection {
    Radio,
    Settings
}

enum class SecondaryRoute {
    Connection,
    Monitor,
    CarConfiguration,
    ChannelConfiguration,
    ChannelManagement,
    ChannelCreation,
    LogAnalysis,
    PackageManagement,
    GenericProfiles,
    VoiceProfiles,
    SystemReadiness,
}

data class NavigationState(
    val appSection: AppSection = AppSection.Radio,
    val secondaryRoute: SecondaryRoute? = null,
    val configuredChannelId: String? = null,
    val creatingImplementationId: String? = null,
    val creatingDisplayName: String = ""
)

sealed interface NavigationAction {
    object NavigateToRadio : NavigationAction
    object NavigateToSettingsHome : NavigationAction
    data class NavigateToRsmSetup(val readyForMonitor: Boolean) : NavigationAction
    object NavigateToCarSetup : NavigationAction
    data class NavigateToChannelConfiguration(val channelId: String) : NavigationAction
    object NavigateToChannelManagement : NavigationAction
    data class NavigateToChannelCreation(val implementationId: String, val displayName: String) : NavigationAction
    data class NavigateBack(
        val cleanupPackageManagement: () -> Unit,
        val exitVoiceProfileEditor: () -> Unit
    ) : NavigationAction
    object NavigateToLogAnalysis : NavigationAction
    object NavigateToPackageManagement : NavigationAction
    object NavigateToGenericProfiles : NavigationAction
    object NavigateToVoiceProfiles : NavigationAction
    object NavigateToSystemReadiness : NavigationAction
}

fun navigate(state: NavigationState, action: NavigationAction): NavigationState {
    return when (action) {
        is NavigationAction.NavigateToRadio -> {
            state.copy(
                appSection = AppSection.Radio,
                secondaryRoute = null,
                configuredChannelId = null,
                creatingImplementationId = null
            )
        }
        is NavigationAction.NavigateToSettingsHome -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = null,
                configuredChannelId = null,
                creatingImplementationId = null
            )
        }
        is NavigationAction.NavigateToRsmSetup -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = if (action.readyForMonitor) SecondaryRoute.Monitor else SecondaryRoute.Connection
            )
        }
        is NavigationAction.NavigateToCarSetup -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.CarConfiguration
            )
        }
        is NavigationAction.NavigateToChannelConfiguration -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.ChannelConfiguration,
                configuredChannelId = action.channelId,
                creatingImplementationId = null
            )
        }
        is NavigationAction.NavigateToChannelManagement -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.ChannelManagement
            )
        }
        is NavigationAction.NavigateToChannelCreation -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.ChannelCreation,
                configuredChannelId = null,
                creatingImplementationId = action.implementationId,
                creatingDisplayName = action.displayName
            )
        }
        is NavigationAction.NavigateBack -> {
            val currentRoute = state.secondaryRoute
            val nextSection: AppSection
            var nextRoute: SecondaryRoute? = state.secondaryRoute

            if (currentRoute != null) {
                exitSettingsChildRoute(
                    isPackageManagement = currentRoute == SecondaryRoute.PackageManagement,
                    isVoiceProfiles = currentRoute == SecondaryRoute.VoiceProfiles,
                    cleanup = action.cleanupPackageManagement,
                    exitVoiceProfileEditor = action.exitVoiceProfileEditor,
                    navigateToParent = {
                        nextRoute = null
                    }
                )
                nextSection = state.appSection
            } else if (state.appSection == AppSection.Settings) {
                nextSection = AppSection.Radio
                nextRoute = null
            } else {
                nextSection = state.appSection
            }

            state.copy(
                appSection = nextSection,
                secondaryRoute = nextRoute,
                configuredChannelId = null,
                creatingImplementationId = null
            )
        }
        is NavigationAction.NavigateToLogAnalysis -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.LogAnalysis
            )
        }
        is NavigationAction.NavigateToPackageManagement -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.PackageManagement
            )
        }
        is NavigationAction.NavigateToGenericProfiles -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.GenericProfiles
            )
        }
        is NavigationAction.NavigateToVoiceProfiles -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.VoiceProfiles
            )
        }
        is NavigationAction.NavigateToSystemReadiness -> {
            state.copy(
                appSection = AppSection.Settings,
                secondaryRoute = SecondaryRoute.SystemReadiness
            )
        }
    }
}

internal fun exitSettingsChildRoute(
    isPackageManagement: Boolean,
    isVoiceProfiles: Boolean = false,
    cleanup: () -> Unit,
    exitVoiceProfileEditor: () -> Unit = {},
    navigateToParent: () -> Unit,
) {
    if (isPackageManagement) cleanup()
    if (isVoiceProfiles) exitVoiceProfileEditor()
    navigateToParent()
}
