## ADDED Requirements

### Requirement: Settings is a first-class application destination
The system SHALL provide persistent top-level navigation between the operational Radio destination and a Settings destination. Entering Settings SHALL NOT change the active channel, selected audio device, playback state, or channel runtime state.

#### Scenario: User opens Settings from Radio
- **WHEN** the user activates the Settings top-level destination from Radio
- **THEN** the system SHALL display the Settings home
- **AND** the active channel and selected audio device SHALL remain unchanged

#### Scenario: User returns to Radio
- **WHEN** the user activates the Radio top-level destination from Settings
- **THEN** the system SHALL display the operational dashboard
- **AND** it SHALL project the current service state rather than resetting dashboard state

### Requirement: Settings groups configuration by ownership
The Settings home SHALL expose distinct groups for Devices and audio, Channels, Integrations and profiles, System, and Advanced configuration. App-wide configuration SHALL reside in Settings; provider-specific configuration for one channel SHALL remain associated with that channel instance.

#### Scenario: Settings hierarchy is displayed
- **WHEN** the Settings home is displayed
- **THEN** the user SHALL be able to identify separate Devices and audio, Channels, Integrations and profiles, System, and Advanced groups
- **AND** each available group SHALL describe the configuration it owns

#### Scenario: User opens one channel's configuration
- **WHEN** the user activates configuration for a channel instance from Radio or Settings
- **THEN** the system SHALL open the same host-rendered configuration destination addressed by that instance's stable ID
- **AND** it SHALL preserve the active-channel selection

### Requirement: Existing app-wide management is reachable through Settings
The Settings hierarchy SHALL provide explicit navigation to RSM setup or monitoring, car-headset configuration, channel catalogue management, installed provider packages, provider-published profiles, voice profiles, system readiness and recovery actions, and diagnostic log analysis when those destinations are available. These destinations SHALL NOT require entering channel management mode or discovering a hidden long-press gesture.

#### Scenario: User opens device configuration
- **WHEN** the user activates an available Radio or Car row under Devices and audio
- **THEN** the system SHALL open the corresponding explicit setup, monitoring, or configuration destination

#### Scenario: User opens integration administration
- **WHEN** the user activates installed providers or profiles under Integrations and profiles
- **THEN** the system SHALL open the existing host-owned management destination

#### Scenario: User opens diagnostic logs
- **WHEN** the user activates diagnostic logs under Advanced
- **THEN** the system SHALL open Log Analysis
- **AND** the user SHALL NOT need to long-press an application title

### Requirement: Secondary Settings destinations have consistent back navigation
Every secondary destination opened from Settings SHALL display a visible Back affordance. Back SHALL return from the secondary destination to its owning Settings destination or Settings home before leaving the Settings top-level section.

#### Scenario: User returns from a secondary Settings screen
- **WHEN** the user activates Back from package management, profile management, device setup, car configuration, system readiness, or diagnostics opened through Settings
- **THEN** the system SHALL return to the appropriate Settings parent
- **AND** it SHALL NOT jump directly to Radio

#### Scenario: System Back follows Settings hierarchy
- **WHEN** the user invokes system Back from a secondary Settings destination
- **THEN** the result SHALL match the visible Back affordance

### Requirement: Dashboard device selection and Settings device setup are separate actions
The Radio dashboard SHALL retain audio-device selection for live routing preference. Settings SHALL own device setup and monitoring. Selecting a device SHALL NOT open setup, and opening setup SHALL NOT select a device unless an existing explicit setup result requires a mode transition.

#### Scenario: User selects an available audio device
- **WHEN** the user taps an available audio-device tile on Radio
- **THEN** the system SHALL select that device's mode for later playback admission
- **AND** it SHALL NOT navigate to Settings

#### Scenario: User needs to configure an unavailable device
- **WHEN** an audio-device tile is unavailable
- **THEN** the tile SHALL expose its unavailable state without a hidden setup gesture
- **AND** the corresponding explicit setup destination SHALL remain reachable under Settings
