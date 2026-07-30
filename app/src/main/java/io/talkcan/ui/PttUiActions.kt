package io.talkcan.ui

import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.InputMode
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.profile.ProfileId

/** Host-owned UI intents. Provider configuration is always addressed by stable IDs and opaque data. */
interface PttUiActions {
    fun requestPermissions()
    fun requestManageExternalStorage()
    fun pickDirectory(configurationOwnerId: String, fieldId: String)
    /**
     * 2.7: Launch the generic SAF directory-tree picker for one declared mount,
     * keyed by configuration owner instance + provider implementation + mount
     * declaration ID. Distinct from [pickDirectory], which addresses a scalar
     * [io.talkcan.model.ChannelConfigurationField.DirectoryField].
     */
    fun pickMount(request: MountSelectionRequest)
    fun openBluetoothSettings()
    fun scanForDevice()
    fun pairTarget()
    fun refreshCarHfpConfiguration()
    fun selectCarHfpCandidate(selectionId: String)
    fun connectSerial()
    fun retry()
    fun disconnectSerial()
    fun setActiveChannel(id: String)
    fun setInputMode(mode: InputMode)
    fun navigateToRadio()
    fun navigateToSettingsHome()
    fun navigateToRsmSetup()
    fun navigateToCarSetup()
    fun navigateToChannelConfiguration(channelId: String)
    fun navigateToChannelManagement()
    fun navigateToChannelCreation(implementationId: ChannelImplementationId, displayName: String)
    fun navigateBack()
    fun navigateToLogAnalysis()
    fun phonePttPressed(channelId: String)
    fun phonePttReleased(channelId: String)
    fun createChannel(
        implementationId: ChannelImplementationId,
        displayName: String,
        payload: OpaqueJsonObject,
    ): String?
    fun updateChannelConfiguration(channelId: String, payload: OpaqueJsonObject): String?
    fun removeChannel(id: String)
    fun moveChannel(id: String, toIndex: Int)
    fun renameChannel(id: String, newName: String)
    fun navigateToPackageManagement()
    fun resolvePackageRepository(url: String)
    fun selectPackageRelease(releaseId: String)
    fun confirmPackageInstall(acknowledged: Boolean)
    fun rollbackPackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity)
    fun removePackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity)
    fun cancelPackageInspection()
    fun refreshPackageManagement(url: String)

    // ── Generic package profiles (5.1–5.5) ─────────────────────────────────
    /** Navigate to the generic profile-management surface. */
    fun navigateToGenericProfiles()
    /**
     * Create one global profile of a published package type. Secret plaintext
     * crosses this boundary once, directly into protected storage; it is
     * never redisplayed.
     */
    fun createGenericProfile(
        identity: io.talkcan.profile.ProfileTypeIdentity,
        displayName: String,
        scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
        secrets: Map<String, CharSequence>,
    )
    /**
     * Edit one retained profile. Null scalars preserve exact values; secret
     * edits carry retain/replace/clear semantics with write-only plaintext.
     */
    fun updateGenericProfile(
        profileId: ProfileId,
        displayName: String,
        scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
        secretEdits: Map<String, io.talkcan.profile.SecretEditAction>,
    )
    /** Explicitly delete one profile; channel selections are never rewritten. */
    fun deleteGenericProfile(profileId: ProfileId)
    /** Navigate to the voice-profile management and mixer surface (Task 5.1). */
    fun navigateToVoiceProfiles()
    /** Navigate to system readiness screen. */
    fun navigateToSystemReadiness()
}
