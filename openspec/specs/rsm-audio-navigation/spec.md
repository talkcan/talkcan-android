# Capability: rsm-audio-navigation

## Purpose
Defines RSM hardware-driven channel navigation, synchronized dashboard selection, and headset audio feedback.

## Requirements

### Requirement: RSM Control Mode Navigation
The system SHALL map the `B02PTT-FF01` physical Group button, Volume Up button, Volume Down button, and PTT button to drive a system-level navigation menu. Channel traversal SHALL follow the visual catalogue direction and SHALL saturate without wrapping.

#### Scenario: Entering Control Mode
- **WHEN** the user presses the Group button while in Active Mode
- **THEN** the hardware SHALL enter Control Mode

#### Scenario: Navigating to preceding channel
- **WHEN** the user clicks the Volume Up button while in Control Mode and the active channel is not first
- **THEN** the system SHALL calculate the preceding channel in catalogue order
- **AND** the system SHALL set that channel as the globally active channel

#### Scenario: Navigating to following channel
- **WHEN** the user clicks the Volume Down button while in Control Mode and the active channel is not last
- **THEN** the system SHALL calculate the following channel in catalogue order
- **AND** the system SHALL set that channel as the globally active channel

#### Scenario: Confirming channel selection
- **WHEN** the user presses the PTT button while in Control Mode
- **THEN** the system SHALL return the hardware to Active Mode without transmitting audio

### Requirement: Headless Audio Feedback
The system SHALL render navigation announcements on demand from the current channel catalogue text using the installed offline Android TTS engine, synthesize each requested phrase to transient normalized 16 kHz mono PCM16, and play it through the existing app-owned exact target-RSM playback path. The system SHALL apply strict latest-wins ownership across synthesis and active navigation playback: a newer navigation request SHALL cancel any in-flight synthesis or owned playback of a prior request before the newer request proceeds. When one bounded TTS reinitialization or probe recovery is active, a newer navigation request SHALL replace only the latest pending generation and phrase text and SHALL let that recovery continue; on successful recovery the system SHALL retry only the newest pending announcement. A traversal attempt beyond either catalogue boundary SHALL play exactly one error beep through the same app-owned playback path. The system SHALL NOT play a cache-miss or model-not-ready fallback beep; if an announcement cannot be synthesized it SHALL be dropped silently. When unrelated host audio owns admission, the system SHALL drop the navigation announcement rather than preempt capture or unrelated playback.

#### Scenario: Menu entry announcement
- **WHEN** the hardware enters Control Mode
- **AND** host audio admission is free
- **THEN** the system SHALL synthesize the fixed literal "Channels" announcement text on demand to transient 16 kHz mono PCM16
- **AND** play it through the existing app-owned exact target-RSM playback route

#### Scenario: Channel selection announcement
- **WHEN** the user switches channels via Volume Up or Volume Down
- **AND** host audio admission is free
- **THEN** the system SHALL cancel any currently in-flight synthesis or playing navigation announcement as terminal and teardown-ordered
- **AND** resolve the current name of the new active channel from the catalogue
- **AND** synthesize it on demand to transient 16 kHz mono PCM16
- **AND** play it through the existing app-owned exact target-RSM playback route

#### Scenario: Top boundary error feedback
- **WHEN** the first catalogue channel is active and the user clicks Volume Up in Control Mode
- **AND** host audio admission is free
- **THEN** the active channel SHALL remain unchanged
- **AND** the system SHALL cancel any currently in-flight synthesis or playing navigation announcement as terminal and teardown-ordered
- **AND** play exactly one error beep through the app-owned exact target-RSM playback route
- **AND** release the acquired route exactly once

#### Scenario: Bottom boundary error feedback
- **WHEN** the last catalogue channel is active and the user clicks Volume Down in Control Mode
- **AND** host audio admission is free
- **THEN** the active channel SHALL remain unchanged
- **AND** the system SHALL cancel any currently in-flight synthesis or playing navigation announcement as terminal and teardown-ordered
- **AND** play exactly one error beep through the app-owned exact target-RSM playback route
- **AND** release the acquired route exactly once

#### Scenario: Confirmation announcement
- **WHEN** the user presses PTT to confirm selection and exit Control Mode
- **AND** host audio admission is free
- **THEN** the system SHALL cancel any currently in-flight synthesis or playing navigation announcement as terminal and teardown-ordered
- **AND** resolve the current confirmation phrase text from the catalogue
- **AND** synthesize it on demand to transient 16 kHz mono PCM16
- **AND** play it through the existing app-owned exact target-RSM playback route

#### Scenario: Navigation announcement is dropped when host audio is owned
- **WHEN** a navigation announcement or boundary beep is triggered
- **AND** unrelated host audio owns admission
- **THEN** the system SHALL drop the navigation announcement or beep without preempting the owning audio
- **AND** SHALL discard any already-synthesized transient PCM without acquiring a playback route

#### Scenario: Latest-wins cancels prior in-flight synthesis
- **WHEN** a newer navigation request arrives while a prior request's synthesis is in flight
- **THEN** the system SHALL cancel the prior synthesis as terminal
- **AND** SHALL discard the prior request's transient PCM
- **AND** the newer request SHALL proceed through its own synthesis and normal host admission, dropping if unrelated audio owns admission at playback time

#### Scenario: Latest-wins cancels prior active playback
- **WHEN** a newer navigation request arrives while a prior request's playback is active
- **THEN** the system SHALL stop the prior playback
- **AND** SHALL wait for AudioTrack cleanup and release the route
- **AND** SHALL clear only the matching coordinator owner before the newer request proceeds

#### Scenario: Newer request replaces pending text during bounded recovery
- **WHEN** one bounded TTS reinitialization or probe recovery is active for a prior announcement
- **AND** a newer navigation request arrives before recovery completes
- **THEN** the system SHALL NOT cancel the active recovery
- **AND** SHALL replace only the latest pending generation and phrase text with the newer request's text
- **AND** on successful recovery SHALL retry only the newest pending announcement

#### Scenario: Synthesis failure drops the announcement
- **WHEN** on-demand synthesis of a navigation phrase fails after any permitted recovery
- **THEN** the system SHALL drop the announcement silently
- **AND** SHALL NOT play a cache-miss or model-not-ready fallback beep
- **AND** SHALL release any acquired route
- **AND** the application SHALL transition per the native-navigation-tts readiness classification without entering a fallback announcement path

### Requirement: Synchronized UI Highlighting
The system SHALL automatically synchronize the visual state of the Main Dashboard with the hardware-driven channel selection.

#### Scenario: Hardware scrolls channel list
- **WHEN** the user switches channels via the RSM buttons
- **THEN** the Main Dashboard channel cards SHALL instantly visually reflect the new active channel selection