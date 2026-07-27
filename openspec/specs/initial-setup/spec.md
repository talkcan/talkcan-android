# Initial Setup

## Purpose

Define the first-launch setup flow that gates access to the main application until required permissions and runtime models are ready.

## Requirements

### Requirement: Setup presents only known user actions
The system SHALL show the setup surface only after bootstrap has identified a concrete user-resolvable prerequisite: missing runtime permissions, model assets that require explicit download or repair, or a missing or unusable offline English navigation voice that requires an Android TTS voice install action. The system SHALL NOT show setup during passive checking, native speech initialization, controller construction, or autonomous recovery.

#### Scenario: Prerequisites are still unknown
- **WHEN** bootstrap has not completed permission, model, and offline navigation voice inspection
- **THEN** the loading surface is shown
- **AND** the setup surface is not shown

#### Scenario: Permission action is known
- **WHEN** bootstrap identifies one or more missing required runtime permissions
- **THEN** setup shows the permission action and the exact missing state

#### Scenario: Model action is known
- **WHEN** bootstrap identifies an absent, version-mismatched, or hash-invalid model set
- **THEN** setup shows the model download or repair action

#### Scenario: Offline navigation voice action is known
- **WHEN** bootstrap identifies an absent Android TTS engine, a missing or incomplete offline English voice, a failed voice selection, a failed synthesis probe, or a probe timeout
- **THEN** setup shows the offline navigation voice action resolving `ACTION_INSTALL_TTS_DATA` with a `Settings.ACTION_SETTINGS` fallback
- **AND** setup identifies the missing offline English navigation voice as the incomplete step

### Requirement: Setup actions continue automatically
The system SHALL re-evaluate prerequisites after every setup action and SHALL leave setup automatically when no further user action is currently required. The system SHALL NOT require a separate confirmation to continue bootstrap or enter the dashboard.

#### Scenario: Permission grant completes all setup actions
- **WHEN** the permission result grants all required permissions and model assets are already valid and the offline English navigation voice is already proven
- **THEN** the system automatically replaces setup with loading
- **AND** core preparation continues

#### Scenario: Permission grant reveals model action
- **WHEN** the permission result grants all required permissions but model assets require acquisition or repair
- **THEN** setup remains visible with the model action available

#### Scenario: Permission grant reveals offline voice action
- **WHEN** the permission result grants all required permissions and model assets are already valid
- **AND** the offline English navigation voice has not yet been probed
- **THEN** the system replaces setup with loading and probes the offline navigation voice
- **AND** if the probe fails, loading transitions to setup with the offline navigation voice action

#### Scenario: User starts model acquisition
- **WHEN** the user starts the required model download or repair action
- **THEN** the system automatically replaces setup with loading
- **AND** loading shows real acquisition progress

#### Scenario: Model acquisition fails
- **WHEN** user-initiated model acquisition fails and requires an explicit retry
- **THEN** the system returns to setup with the concrete failure and retry action

#### Scenario: Model acquisition completes but offline voice still needed
- **WHEN** user-initiated model acquisition completes and all required model sets pass full verification
- **AND** the offline English navigation voice has not yet been probed
- **THEN** the system re-enters prerequisite checking from loading
- **AND** if the voice probe fails, loading transitions directly to setup with the offline navigation voice action

#### Scenario: Model acquisition completes all remaining setup actions
- **WHEN** user-initiated model acquisition completes and all required model sets pass full verification
- **AND** the subsequent offline English navigation voice probe succeeds
- **THEN** the system continues from loading into core preparation without another setup acknowledgement

#### Scenario: User returns from voice install
- **WHEN** the user returns to Talkcan after launching the `ACTION_INSTALL_TTS_DATA` activity or the `Settings.ACTION_SETTINGS` fallback
- **THEN** the system automatically replaces setup with loading and rechecks the offline English navigation voice prerequisite
- **AND** if the synthesis probe succeeds, loading continues through core preparation without returning to setup
- **AND** if the voice remains missing or the probe fails, loading transitions back to setup with the voice step showing an error icon and the action remaining available

### Requirement: Initial setup screen gates main app
The system SHALL present a setup screen after passive bootstrap checking on first launch or any subsequent launch where a required permission is missing, a model set requires explicit download or repair, or the offline English navigation voice is missing or unproven. The setup screen SHALL gate the dashboard while any user-resolvable prerequisite remains incomplete, and setup completion SHALL return automatically to passive loading for core preparation.

#### Scenario: First launch begins with loading
- **WHEN** the app is launched for the first time after install
- **THEN** the passive loading surface is shown while setup prerequisites are checked
- **AND** setup is shown only after missing permission, model, or offline navigation voice actions are known

#### Scenario: All setup steps complete resumes loading
- **WHEN** all setup steps are completed with permissions granted, model assets fully verified, and a proven offline English navigation voice
- **THEN** the system automatically returns to loading for core initialization
- **AND** no "Enter Talkcan" action is required

#### Scenario: Permission revoked on later launch
- **WHEN** the app is launched and any required runtime permission has been revoked since the previous launch
- **THEN** loading transitions to setup with the permissions step marked incomplete

#### Scenario: Model corrupted on later launch
- **WHEN** the app is launched and any model file's SHA-256 does not match the bundled hash manifest
- **THEN** loading transitions to setup with the model step marked incomplete and an explicit repair action

#### Scenario: Offline navigation voice removed on later launch
- **WHEN** the app is launched and the previously proven offline English navigation voice is no longer available or fails the synthesis probe
- **THEN** loading transitions to setup with the offline navigation voice step marked incomplete
- **AND** the offline navigation voice action is shown

#### Scenario: All checks pass on launch
- **WHEN** the app is launched and all permissions are granted and all model files pass SHA-256 verification and the offline English navigation voice synthesis probe succeeds
- **THEN** the setup screen is skipped
- **AND** loading continues through core initialization before the dashboard is shown

### Requirement: Setup screen shows step status

The setup screen SHALL display each step with a clear status indicator (pending, in progress, completed, failed).

#### Scenario: Step shows pending initially
- **WHEN** the setup screen appears and a step has not been started
- **THEN** the step shows a pending/neutral icon and its action button

#### Scenario: Completed step shows check mark
- **WHEN** a step has been successfully completed
- **THEN** the step shows a green check mark and its action button is hidden or disabled

#### Scenario: In-progress step shows progress
- **WHEN** a step is actively being executed (e.g., downloading models)
- **THEN** the step shows a progress indicator (spinner for permissions, progress bar with percentage for downloads)

#### Scenario: Failed step shows error
- **WHEN** a step has failed (e.g., download error, permission denied, offline voice probe failure)
- **THEN** the step shows an error icon and a retry button

### Requirement: Offline navigation voice action uses public Android intents
The offline navigation voice setup action SHALL build an `ACTION_INSTALL_TTS_DATA` intent with `setPackage` set to the active or default TTS engine package so that devices with multiple engines install voice data for the engine being probed. The system SHALL resolve and launch that explicit intent. If the engine package is absent or the explicit intent resolves to no activity, the action SHALL fall back to `Settings.ACTION_SETTINGS` with explicit user-facing instruction to select a TTS engine and install an offline English voice. The action SHALL NOT launch the raw `com.android.settings.TTS_SETTINGS` component or `Settings.ACTION_VOICE_INPUT_SETTINGS`. The system SHALL recheck the offline English navigation voice synthesis probe when the user returns from either intent path.

#### Scenario: Install-data intent resolves for the active engine
- **WHEN** the user taps the offline navigation voice action and the active or default TTS engine package is known
- **AND** the explicit `ACTION_INSTALL_TTS_DATA` intent with `setPackage` resolves to an activity
- **THEN** the system launches that engine's activity to install offline voice data for the engine being probed

#### Scenario: Engine package absent falls back to settings
- **WHEN** the user taps the offline navigation voice action and no active or default TTS engine package is available
- **THEN** the system launches `Settings.ACTION_SETTINGS` with explicit instruction to install an offline English voice

#### Scenario: Explicit install-data intent has no handler falls back to settings
- **WHEN** the user taps the offline navigation voice action and the engine package is known
- **AND** the explicit `ACTION_INSTALL_TTS_DATA` intent with `setPackage` resolves to no activity
- **THEN** the system launches `Settings.ACTION_SETTINGS` with explicit instruction to install an offline English voice

#### Scenario: Hidden intent paths are not used
- **WHEN** the offline navigation voice action is launched
- **THEN** the system SHALL NOT launch `com.android.settings.TTS_SETTINGS` directly
- **AND** SHALL NOT launch `Settings.ACTION_VOICE_INPUT_SETTINGS`

#### Scenario: Return from voice install rechecks probe
- **WHEN** the user returns from either the `ACTION_INSTALL_TTS_DATA` activity or the `Settings.ACTION_SETTINGS` fallback
- **THEN** the system automatically rechecks the offline English navigation voice synthesis probe

### Requirement: Permissions step requests all runtime permissions

The system SHALL request all required runtime permissions in a single batch when the user taps the permissions action button.

#### Scenario: Grant all permissions
- **WHEN** the user taps "Grant permissions" on the setup screen
- **THEN** the system launches Android's runtime permission dialog for BLUETOOTH_CONNECT, BLUETOOTH_SCAN, RECORD_AUDIO, and POST_NOTIFICATIONS simultaneously

#### Scenario: All permissions granted
- **WHEN** the user grants all requested permissions
- **THEN** the permissions step shows a green check mark and the storage-access step becomes actionable

#### Scenario: Some permissions denied
- **WHEN** the user denies one or more requested permissions
- **THEN** the permissions step shows which permissions are still missing and the "Grant permissions" button remains available

### Requirement: Storage access uses Android's dedicated settings flow

The system SHALL require all-files access before model acquisition because Journal persists to
user-selected real filesystem paths for external synchronization.

#### Scenario: Request all-files access
- **WHEN** runtime permissions are granted and the user taps "Allow storage access"
- **THEN** the system opens Android's app-specific all-files access settings page

#### Scenario: Return with storage access granted
- **WHEN** the user grants all-files access and returns to Talkcan
- **THEN** bootstrap rechecks its prerequisites automatically
- **AND** the storage-access step shows a green check mark
- **AND** the model download step becomes actionable

#### Scenario: Storage access remains denied
- **WHEN** all-files access is missing
- **THEN** bootstrap remains on the setup screen
- **AND** model acquisition and core initialization do not start

### Requirement: ConnectionScreen no longer shows permissions

The RSM device setup page (ConnectionScreen) SHALL NOT include any permission-requesting UI elements.

#### Scenario: ConnectionScreen without permissions
- **WHEN** the user navigates to the RSM setup page
- **THEN** the page does not show a "Grant permissions" button, a permissions status row, or permissions guidance text

#### Scenario: ConnectionScreen still shows other readiness checks
- **WHEN** the user navigates to the RSM setup page
- **THEN** Bluetooth enablement, device presence, SPP state, and headset audio state are still displayed and actionable