## ADDED Requirements

### Requirement: Radio provides one fixed phone PTT control
The system SHALL render exactly one phone PTT control in a fixed operational region of the Radio destination. The region SHALL remain outside dashboard scrolling content and SHALL retain the same bounds and center point while channels scroll, reorder, change height, change selection, or update runtime state.

#### Scenario: Channel list scrolls
- **WHEN** the user scrolls the channel list
- **THEN** the phone PTT region SHALL remain visible at the same screen position

#### Scenario: Active channel changes
- **WHEN** the user selects a different active channel
- **THEN** the phone PTT region SHALL retain its position
- **AND** its destination label SHALL update to the newly active channel

#### Scenario: PTT is temporarily unavailable
- **WHEN** the phone PTT control is disabled by missing selection, active-session ownership, or admission state
- **THEN** the region SHALL remain mounted at the same position
- **AND** it SHALL explain why phone PTT is unavailable

### Requirement: Fixed phone PTT targets only the active channel
The fixed phone PTT control SHALL resolve its destination from the authoritative active channel at gesture start. It SHALL NOT select a channel or derive a destination from channel-row position.

#### Scenario: Phone PTT starts for active channel
- **WHEN** an active ready channel exists and the user presses the fixed phone PTT control
- **THEN** the system SHALL start phone-originated PTT for that active channel's stable instance ID
- **AND** no other channel selection SHALL change

#### Scenario: No active channel exists
- **WHEN** no active channel exists
- **THEN** the fixed phone PTT region SHALL display a disabled choose-channel state
- **AND** pressing it SHALL NOT start capture

#### Scenario: Active channel is unavailable
- **WHEN** the active channel is not ready and the user presses the fixed phone PTT control
- **THEN** the system SHALL apply existing host-owned not-ready admission and feedback behavior for that channel
- **AND** it SHALL NOT execute provider code or start capture

### Requirement: Phone PTT uses strict press-and-hold timing
Pointer down on the enabled fixed phone PTT control SHALL request phone PTT admission. Pointer up, gesture cancellation, focus loss, app stop, maximum-duration cutoff, capture failure, or service teardown SHALL terminate or cancel that phone-owned session according to existing session timing. The fixed control SHALL NOT require a long-press delay and SHALL NOT support slide-to-lock.

#### Scenario: Press and release records one message
- **WHEN** the user presses and holds the fixed phone PTT control on a ready active channel
- **THEN** the system SHALL play the ready beep on the admitted phone route
- **AND** recording SHALL start only after the beep completes while the press remains held
- **AND** release SHALL finish the capture as one outbound message

#### Scenario: Release during ready beep cancels capture
- **WHEN** the user releases the fixed phone PTT control before the ready beep completes
- **THEN** the system SHALL cancel the pending phone PTT session
- **AND** it SHALL NOT begin recording

#### Scenario: Gesture moves while held
- **WHEN** phone PTT is active and the pointer moves within or outside the control before release
- **THEN** the system SHALL keep the session held until release or cancellation
- **AND** horizontal movement SHALL NOT lock the session

#### Scenario: Focus is lost
- **WHEN** a phone PTT session is pending or recording and the app loses foreground interaction
- **THEN** the system SHALL terminate the phone-owned session

### Requirement: Fixed phone PTT auto-selects the phone audio device
An admitted press of the fixed phone PTT control SHALL auto-transition the selected mode to `OnAPinch` before phone-route acquisition. Later admitted incoming playback SHALL use the selected `OnAPinch` output policy until manual device selection or another admitted actuator changes the mode.

#### Scenario: Phone PTT starts while Radio is selected
- **WHEN** `Work` is selected and the user presses the fixed phone PTT control while admission is available
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** capture SHALL use the phone route
- **AND** the dashboard SHALL show Phone as the selected audio device

#### Scenario: Phone PTT is rejected during playback
- **WHEN** another channel-content playback operation owns half-duplex admission and the user presses fixed phone PTT
- **THEN** the system SHALL reject phone PTT according to existing policy
- **AND** it SHALL NOT apply the phone home-mode transition

### Requirement: Fixed PTT region projects capture ownership and state
The fixed operational region SHALL display the active channel and phone PTT readiness while idle. During pending capture, recording, finalization, or release, it SHALL display the current phase and owning audio device. A non-phone actuator's active session SHALL disable phone input without hiding the region or implying that Phone owns the session.

#### Scenario: Phone owns capture
- **WHEN** phone PTT is pending or active
- **THEN** the region SHALL identify phone capture and its current state
- **AND** release guidance SHALL be visible while recording

#### Scenario: RSM owns capture
- **WHEN** an RSM PTT session owns capture admission
- **THEN** the region SHALL identify recording on Radio
- **AND** the phone PTT action SHALL be disabled until ownership terminates

#### Scenario: Car owns capture
- **WHEN** a car PTT session owns capture admission
- **THEN** the region SHALL identify recording in Car mode
- **AND** the phone PTT action SHALL be disabled until ownership terminates

### Requirement: Fixed phone PTT is available to every functional active channel
Every functional channel supported by the shared PTT dispatch path SHALL be addressable through the fixed phone PTT control after that channel becomes active. Channel type, catalogue position, provider identity, and card height SHALL NOT create separate phone PTT surfaces.

#### Scenario: User selects another functional channel
- **WHEN** the user selects any functional channel and presses fixed phone PTT
- **THEN** the system SHALL dispatch through the shared phone PTT path to that channel
- **AND** no channel-card long-press SHALL be required

### Requirement: Fixed phone PTT survives unrelated device lifecycles
A pending or active phone PTT session SHALL remain unchanged by RSM serial reconnect failure, RSM serial stream loss, explicit RSM serial disconnect, or stale Telecom lifecycle callbacks. Only phone-owned terminal events and existing whole-session validity failures SHALL terminate it.

#### Scenario: Phone PTT survives RSM reconnect failure
- **WHEN** a phone PTT session remains held
- **AND** an automatic RSM serial reconnect fails
- **THEN** phone capture SHALL continue
- **AND** no synthetic phone release SHALL occur

#### Scenario: Phone PTT survives stale Telecom callback
- **WHEN** phone PTT is pending or active
- **AND** a timeout or terminal callback arrives from an earlier Telecom operation
- **THEN** the phone session SHALL remain unchanged

### Requirement: Fixed phone PTT exposes accessible semantics
The fixed phone PTT control SHALL expose button role, active-channel destination, enabled state, and capture state to accessibility services. Channel selection rows SHALL expose selection semantics and SHALL NOT advertise PTT actions. PTT state SHALL NOT be communicated by color alone.

#### Scenario: Accessibility service inspects idle PTT
- **WHEN** a ready active channel exists and an accessibility service focuses the fixed control
- **THEN** the service SHALL announce that the control talks to the active channel
- **AND** it SHALL expose an actionable PTT semantic

#### Scenario: Accessibility service inspects a channel row
- **WHEN** an accessibility service focuses a channel row
- **THEN** the row SHALL expose its selected or unselected state and activation action
- **AND** it SHALL NOT expose long-press-to-talk or slide-lock instructions

### Requirement: Talk follows channel duplex mode
The strict hold requirements apply to half-duplex channels. For a full-duplex
channel, a Talk click SHALL start a conversation and a subsequent click SHALL
stop it. The control SHALL retain its fixed position and identify its action.

#### Scenario: Full-duplex Talk toggles
- **WHEN** the selected channel is full-duplex and the user clicks Talk
- **THEN** its conversation SHALL start without requiring a held gesture
- **AND** another click SHALL stop that conversation

### Requirement: Held SOS independently addresses the priority channel
The user SHALL configure one priority channel of either duplex mode. SOS
long-press SHALL end a running regular full-duplex conversation and start the
priority interaction without changing regular selection. Release SHALL send a
half-duplex recording or stop a full-duplex conversation. The regular
conversation SHALL NOT resume automatically.

#### Scenario: Release during startup
- **WHEN** SOS is released before priority startup finishes
- **THEN** the host SHALL cancel startup and close any admitted priority session
- **AND** a late completion SHALL NOT leave the microphone active
