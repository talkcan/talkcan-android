## Purpose

TBD. Defines how Android Auto steering-wheel media controls act as a latched virtual PTT button for Talkcan, including fail-safe release behavior and driver-safe feedback.

## Requirements

### Requirement: Android Auto media controls expose virtual PTT
The system SHALL expose a media session that allows Android Auto steering-wheel media play/pause controls to act as a latched virtual PTT button for Talkcan.

#### Scenario: Play/pause starts virtual PTT from released state
- **WHEN** the car media play/pause control is activated while virtual PTT is released
- **AND** the active channel is ready
- **THEN** the system SHALL emit a car-originated PTT press for the active channel
- **AND** mark virtual PTT as pressed

#### Scenario: Play/pause releases virtual PTT from pressed state
- **WHEN** the car media play/pause control is activated while virtual PTT is pressed
- **THEN** the system SHALL emit a car-originated PTT release
- **AND** mark virtual PTT as released

#### Scenario: Stop-like media command releases virtual PTT
- **WHEN** virtual PTT is pressed
- **AND** the system receives a car media pause, stop, or other stop-like transport command
- **THEN** the system SHALL emit a car-originated PTT release
- **AND** mark virtual PTT as released

### Requirement: Virtual PTT fails safe to released
The system SHALL force-release virtual PTT whenever the car media control path becomes unavailable or capture safety cannot be guaranteed.

#### Scenario: Android Auto disconnects during virtual PTT
- **WHEN** virtual PTT is pressed
- **AND** Android Auto or the car media session disconnects
- **THEN** the system SHALL emit a car-originated PTT release
- **AND** mark virtual PTT as released

#### Scenario: Service stops during virtual PTT
- **WHEN** virtual PTT is pressed
- **AND** the foreground service is stopping or being destroyed
- **THEN** the system SHALL emit a car-originated PTT release before releasing capture resources

#### Scenario: Capture reaches maximum duration
- **WHEN** a car-originated PTT session reaches the maximum allowed capture duration
- **THEN** the system SHALL stop recording according to the same max-duration behavior used by other PTT sources
- **AND** mark virtual PTT as released

#### Scenario: Capture cannot start
- **WHEN** the car media play/pause control is activated while virtual PTT is released
- **AND** the system cannot start a valid PTT session
- **THEN** virtual PTT SHALL remain released
- **AND** the system SHALL provide error feedback through the resolved audio route when possible

### Requirement: Virtual PTT provides driver-safe feedback
The system SHALL provide visible and audible feedback for car-originated virtual PTT state transitions, with the visible feedback projected onto the Android Auto Media now-playing card as a contract combining the active channel's identity, the live PTT state pill, and a compact pending-backlog summary.

#### Scenario: Virtual PTT starts recording
- **WHEN** car-originated virtual PTT starts a capture
- **THEN** the system SHALL expose recording state through the media session playback state
- **AND** the now-playing metadata title SHALL be the active channel's display name
- **AND** the now-playing metadata subtitle SHALL include a recording state pill
- **AND** the now-playing bitmap SHALL be the recording-state tinted bitmap from the Talkcan visual identity palette
- **AND** the system SHALL play the same ready/start feedback used by the PTT capture path

#### Scenario: Virtual PTT stops recording with a response
- **WHEN** car-originated virtual PTT ends a capture and response audio is available
- **THEN** the system SHALL expose finalizing state through the media session playback state
- **AND** the now-playing metadata subtitle SHALL include a finalizing state pill
- **AND** the now-playing bitmap SHALL be the finalizing-state tinted bitmap from the Talkcan visual identity palette
- **AND** the system SHALL play the response via the A2DP path per the on-the-road PTT session spec
- **AND** the system SHALL provide audible completion feedback after playback when available

#### Scenario: Virtual PTT stops recording without a response
- **WHEN** car-originated virtual PTT ends a capture and no response audio is available
- **THEN** the system SHALL expose ready state through the media session playback state
- **AND** the now-playing metadata subtitle SHALL include an active or ready state pill
- **AND** the now-playing bitmap SHALL be the ready-state tinted bitmap
- **AND** the system SHALL not imply pending audio when none exists

#### Scenario: Now-playing subtitle conveys pending backlog
- **WHEN** the active channel has pending unheard messages greater than zero
- **THEN** the now-playing metadata subtitle SHALL append a compact pending summary such as "<count> pending"
- **WHEN** an inactive channel has pending unheard messages greater than zero
- **THEN** the now-playing metadata subtitle MAY append a compact per-channel pending summary
- **AND** the subtitle SHALL remain under 40 characters and SHALL truncate the pending portion first

#### Scenario: NotReady state surfaces without implying capture capability
- **WHEN** the live PTT state is NotReady (permissions missing, headset audio unavailable, or active channels not ready)
- **THEN** the now-playing metadata title SHALL still reflect the active channel's display name when one is selected
- **AND** the subtitle SHALL include a not-ready state pill
- **AND** the now-playing bitmap SHALL be the not-ready tinted bitmap
- **AND** the system SHALL NOT imply that a capture can start

### Requirement: Virtual PTT is capture-first while car mode is active
The system SHALL treat car media play/pause as virtual PTT control while the Talkcan car PTT media session is active.

#### Scenario: Media play command arrives while idle
- **WHEN** the Talkcan car PTT media session is active
- **AND** virtual PTT is released
- **AND** the system receives a media play command
- **THEN** the command SHALL be interpreted as a virtual PTT press attempt

#### Scenario: Media pause command arrives while recording
- **WHEN** the Talkcan car PTT media session is active
- **AND** virtual PTT is pressed
- **AND** the system receives a media pause command
- **THEN** the command SHALL be interpreted as a virtual PTT release attempt
