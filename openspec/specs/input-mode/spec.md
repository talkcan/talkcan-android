## Purpose

TBD. Defines the InputMode state machine (Work, OnTheRoad, OnAPinch), availability gates, automatic transitions, user selection, and actuator gating for PTT operations.

## Requirements

### Requirement: InputMode state machine
The system SHALL maintain a first-class `InputMode { Work, OnTheRoad, OnAPinch }` state that determines audio route resolution, actuator gating, and feedback surface for PTT operations.

#### Scenario: Default mode on launch
- **WHEN** Talkcan launches with no devices connected
- **THEN** the system SHALL set `InputMode` to `OnAPinch`

#### Scenario: Default mode with RSM connected
- **WHEN** Talkcan launches with the RSM already bonded, SPP connected, and SCO available
- **THEN** the system SHALL set `InputMode` to `Work`

#### Scenario: Default mode with Android Auto connected
- **WHEN** Talkcan launches with Android Auto already projecting (media browser client connected)
- **AND** the RSM is not bonded
- **THEN** the system SHALL set `InputMode` to `OnTheRoad`

### Requirement: Mode availability gates
The system SHALL compute mode availability from semantic endpoint readiness and expose availability to the UI for display. Work availability SHALL be based on target RSM logical readiness rather than `AudioDeviceInfo` product-name identity.

#### Scenario: Work mode available
- **WHEN** Bluetooth/audio permissions are granted
- **AND** Bluetooth is enabled
- **AND** the target RSM `B02PTT-FF01` is bonded
- **AND** the target RSM serial/SPP connection is open for button and monitor events
- **AND** the target RSM is connected in the Bluetooth Headset/HFP profile
- **THEN** `Work` mode SHALL be available for selection

#### Scenario: Work mode available with anonymous SCO transport
- **WHEN** the Work availability inputs are satisfied
- **AND** Android exposes only a generic `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` whose product name does not identify `B02PTT-FF01`
- **THEN** `Work` mode SHALL still be available for selection

#### Scenario: Work mode unavailable
- **WHEN** any Work availability input is false
- **THEN** `Work` mode SHALL be unavailable and greyed out in the selector

#### Scenario: Work and On-the-road modes available together
- **WHEN** the Work availability inputs are satisfied
- **AND** `CarMediaSessionService` has at least one active media browser client connection
- **THEN** `Work` mode SHALL be available for selection
- **AND** `OnTheRoad` mode SHALL be available for selection

#### Scenario: On-the-road mode available
- **WHEN** `CarMediaSessionService` has at least one active media browser client connection
- **THEN** `OnTheRoad` mode SHALL be available for selection

#### Scenario: On-the-road mode unavailable
- **WHEN** no media browser client is connected to `CarMediaSessionService`
- **THEN** `OnTheRoad` mode SHALL be unavailable and greyed out in the selector

#### Scenario: On-a-pinch mode always available
- **WHEN** the system is running
- **THEN** `OnAPinch` mode SHALL always be available for selection

### Requirement: Automatic mode transitions on device events
The system SHALL automatically transition between modes based on device connectivity events.

#### Scenario: Android Auto connects
- **WHEN** Android Auto connects (media browser client arrives) while in any mode
- **THEN** the system SHALL transition to `OnTheRoad`

#### Scenario: RSM connects from On-a-pinch
- **WHEN** the RSM bonds and SPP connects while in `OnAPinch`
- **THEN** the system SHALL transition to `Work`

#### Scenario: RSM connects from On-the-road
- **WHEN** the RSM bonds and SPP connects while in `OnTheRoad`
- **THEN** the system SHALL remain in `OnTheRoad`
- **AND** `Work` mode SHALL become available in the selector

#### Scenario: On-the-road disconnect with RSM bonded
- **WHEN** Android Auto disconnects while in `OnTheRoad`
- **AND** the RSM is bonded
- **THEN** the system SHALL transition to `Work`

#### Scenario: On-the-road disconnect without RSM
- **WHEN** Android Auto disconnects while in `OnTheRoad`
- **AND** the RSM is not bonded
- **THEN** the system SHALL transition to `OnAPinch`

#### Scenario: Work disconnect with Android Auto connected
- **WHEN** the RSM disconnects while in `Work`
- **AND** Android Auto is connected (media browser client present)
- **THEN** the system SHALL transition to `OnTheRoad`

#### Scenario: Work disconnect without Android Auto
- **WHEN** the RSM disconnects while in `Work`
- **AND** Android Auto is not connected
- **THEN** the system SHALL transition to `OnAPinch`

### Requirement: User mode selection
The system SHALL allow the user to select any available mode from the main
dashboard. If the Work/RSM mode is unavailable, activating the Work/RSM tile SHALL
open the RSM setup flow instead of transitioning modes.

#### Scenario: User selects an available mode
- **WHEN** the user taps an available mode in the mode selector
- **THEN** the system SHALL transition to that mode
- **AND** apply the mode's audio route policy and actuator gating

#### Scenario: User taps unavailable Work mode
- **WHEN** the user taps the Work/RSM mode tile while Work mode is unavailable
- **THEN** the system SHALL not transition to Work mode
- **AND** the system SHALL open the RSM setup flow

#### Scenario: User taps unavailable non-Work mode
- **WHEN** the user taps an unavailable non-Work mode
- **THEN** the system SHALL not transition and SHALL not register a mode-selection tap on the greyed-out control

#### Scenario: Rules re-assert after user selection
- **WHEN** the user selects a mode and a subsequent device event triggers a transition rule
- **THEN** the rule SHALL take effect regardless of the user's prior selection

### Requirement: PTT routing respects readiness
The system SHALL evaluate the target channel's readiness state before dispatching a PTT capture, regardless of which input mode or actuator initiated the PTT. Route resolution SHALL be based on the active `InputMode` after actuator auto-transition succeeds, and each mode's route acquisition SHALL prove ownership of its semantic endpoint before capture begins.

#### Scenario: PTT on a ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL acquire the Work audio route by proving target RSM HFP ownership
- **AND** resolve the audio route for `Work` mode using the target RSM-owned SCO transport
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnTheRoad` mode (Telecom self-call for car SCO)
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnAPinch` mode (default local phone route, not Work or car SCO)
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: Phone PTT on a ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is true
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel as the active channel
- **AND** resolve the audio route for `OnAPinch` mode
- **AND** dispatch the capture to that channel's designated controller

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **THEN** the system SHALL transition to `Work` mode
- **AND** acquire the target RSM-owned Work route
- **AND** resolve the audio route for `Work` mode
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: RSM PTT Work route acquisition fails closed
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **BUT** the system cannot prove target RSM HFP ownership of the SCO transport
- **THEN** the system SHALL NOT dispatch capture to the active channel's designated controller
- **AND** the system SHALL NOT resolve capture through the OnTheRoad car route
- **AND** the system SHALL leave no active Work route lease behind

#### Scenario: Android Auto play/pause auto-transitions to On-the-road mode
- **WHEN** the Android Auto play/pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad` mode
- **AND** resolve the audio route for `OnTheRoad` mode
- **AND** dispatch the capture to the active channel's designated controller
### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved home-mode audio route if PTT is pressed while the target channel is not ready and that home-mode route can be safely acquired. The system SHALL NOT play error feedback through a different mode's endpoint when actuator auto-transition or route acquisition fails.

#### Scenario: PTT on a not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is false
- **AND** target RSM HFP ownership of the Work SCO route can be proven
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route (target RSM-owned SCO)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Work not-ready beep skipped when RSM route ownership cannot be proven
- **WHEN** PTT is pressed for the Work/RSM actuator path
- **AND** the active channel's `isReady` state is false or route acquisition fails
- **AND** target RSM HFP ownership of the Work SCO route cannot be proven
- **THEN** the system SHALL NOT play the error beep through the car route or any non-Work endpoint
- **AND** the system SHALL drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the On-the-road mode audio route when possible
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch mode audio route (media/local output)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Phone PTT on a not-ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is false
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel as the active channel
- **AND** play a two-tone error beep on the On-a-pinch mode audio route
- **AND** drop the PTT capture without routing to a controller

### Requirement: Actuator auto-transition
The system SHALL automatically transition to the home mode of any actuator that is pressed when that home mode is available, and then dispatch the PTT using that mode's route acquisition rules. If the home mode is unavailable or route acquisition fails, the system SHALL fail closed without routing capture or feedback through a different endpoint.

#### Scenario: RSM PTT pressed from any mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** dispatch the PTT in `Work` mode
- **AND** Work route acquisition SHALL prove target RSM HFP ownership before capture or playback begins

#### Scenario: RSM PTT acquisition fails
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **BUT** target RSM HFP route acquisition fails
- **THEN** the system SHALL NOT start capture
- **AND** the system SHALL NOT use the OnTheRoad car route as fallback
- **AND** the system SHALL NOT play transition-failure feedback through the car route

#### Scenario: Phone channel long-press from any mode
- **WHEN** a channel card is long-pressed on the Android app while in any mode
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** dispatch the PTT in `OnAPinch` mode

#### Scenario: Android Auto play/pause from any mode
- **WHEN** the Android Auto play/pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** dispatch the PTT in `OnTheRoad` mode

#### Scenario: Actuator pressed for unavailable mode
- **WHEN** an actuator is pressed but its home mode is not available
- **THEN** the system SHALL not transition
- **AND** the system SHALL NOT resolve capture or feedback through a different mode's endpoint

### Requirement: Mode-exclusive actuator gating during active capture
The system SHALL prevent a new PTT capture from starting while a PTT audio input session is already active, pending route acquisition, capturing, finalizing, or releasing, regardless of which actuator or mode started it.

#### Scenario: Second actuator press during active capture
- **WHEN** a PTT session is active (capture in progress)
- **AND** any other actuator is pressed
- **THEN** the system SHALL ignore the second press
- **AND** the active capture SHALL continue uninterrupted

#### Scenario: Second actuator press during route acquisition
- **WHEN** a PTT audio input session is acquiring a Work, On-a-pinch, or On-the-road route
- **AND** any other actuator is pressed
- **THEN** the system SHALL ignore or reject the second press
- **AND** the pending session SHALL remain responsible for route setup or cleanup

#### Scenario: Second actuator press during release
- **WHEN** a PTT audio input session is releasing its route
- **AND** any actuator is pressed before release cleanup is complete
- **THEN** the system SHALL NOT let stale release cleanup affect the new press
- **AND** the system SHALL either reject the press until release completes or start the new session with a distinct session identity

### Requirement: Input mode strategies feed the audio input subsystem
The selected input mode SHALL choose an audio input strategy inside the audio input subsystem. Actuator auto-transition SHALL occur before strategy selection, and the selected channel SHALL receive only the resulting channel input contract.

#### Scenario: Actuator selects home mode before strategy
- **WHEN** RSM, phone, or car PTT is pressed
- **THEN** the system applies the actuator home-mode transition rules
- **AND** the audio input subsystem selects the strategy for the resulting mode

#### Scenario: Strategy failure fails closed
- **WHEN** the selected mode strategy cannot prove ownership of its semantic endpoint
- **THEN** the system SHALL NOT dispatch capture to the selected channel
- **AND** the system SHALL NOT fall back to another mode's route
- **AND** the system SHALL leave no active route lease behind

### Requirement: Visible mode selector on main dashboard
The system SHALL display a fixed-height mode selector on the main dashboard
showing all three modes with icon-first availability indicators. The selector
SHALL show Work/RSM with a headset icon, OnTheRoad with a steering-wheel or car
control icon, and OnAPinch with a phone icon. Each mode SHALL remain visible even
when unavailable.

#### Scenario: All modes visible
- **WHEN** the main dashboard is displayed
- **THEN** the selector SHALL show `Work`, `OnTheRoad`, and `OnAPinch` as fixed-height tile controls
- **AND** each tile SHALL include a large mode icon and concise mode label

#### Scenario: Unavailable mode greyed out
- **WHEN** a mode is unavailable
- **THEN** its selector tile SHALL remain visible at the same size as available tiles
- **AND** its unavailable state SHALL be shown through tile color, icon intensity, status text, or an indicator

#### Scenario: Active mode highlighted
- **WHEN** a mode is active
- **THEN** its selector tile SHALL be visually highlighted to indicate the current mode

### Requirement: Work RSM tile opens setup by long press
The Work/RSM mode tile SHALL provide setup access through long-press regardless
of current Work mode availability.

#### Scenario: User long-presses available Work tile
- **WHEN** the dashboard is visible, Work mode is available, and the user long-presses the Work/RSM tile
- **THEN** the system SHALL open the RSM setup or monitor flow
- **AND** the system SHALL NOT change input mode as a result of the long-press

#### Scenario: User long-presses unavailable Work tile
- **WHEN** the dashboard is visible, Work mode is unavailable, and the user long-presses the Work/RSM tile
- **THEN** the system SHALL open the RSM setup flow
- **AND** the system SHALL NOT transition to Work mode

### Requirement: Mode icons follow visual identity
The input mode selector SHALL use app-provided line-art icons that match the
Talkcan visual identity: consistent stroke weight, rounded caps or corners, and
state-driven accent treatment. The selector SHALL NOT use emoji as mode icons.

#### Scenario: Mode selector renders icons
- **WHEN** the main dashboard mode selector is displayed
- **THEN** the Work/RSM tile uses a headset-style icon
- **AND** the OnTheRoad tile uses a steering-wheel or car-control-style icon
- **AND** the OnAPinch tile uses a phone-style icon
- **AND** none of the mode icons are emoji glyphs

### Requirement: Selected mode is authoritative for channel-content output
The selected `InputMode` SHALL determine the semantic output endpoint for every newly admitted channel-content playback operation: `Work` SHALL target the owned RSM HFP/SCO endpoint, `OnTheRoad` SHALL target the car output, and `OnAPinch` SHALL target the phone output. The host SHALL snapshot the mode after playback admission and immediately before route acquisition, and failure to acquire that mode's endpoint SHALL NOT cause fallback to another mode.

#### Scenario: Work response playback is admitted
- **WHEN** channel-content playback is admitted while `Work` is selected
- **THEN** the host SHALL acquire and use the target RSM-owned playback route
- **AND** it SHALL NOT play through the car or phone if Work route acquisition fails

#### Scenario: On-the-road response playback is admitted
- **WHEN** channel-content playback is admitted while `OnTheRoad` is selected
- **THEN** the host SHALL acquire or validate the car playback route
- **AND** it SHALL NOT play through the RSM or phone if the car route is unavailable

#### Scenario: On-a-pinch response playback is admitted
- **WHEN** channel-content playback is admitted while `OnAPinch` is selected
- **THEN** the host SHALL acquire or validate the phone playback route
- **AND** it SHALL NOT play through the RSM or car if the phone route is unavailable

### Requirement: Active playback is not redirected by mode changes
A mode change after playback route acquisition SHALL affect later admission only and SHALL NOT redirect an already active playback operation. After that operation completes, is skipped, is interrupted, or fails and releases its route, every later operation SHALL resolve the then-current mode.

#### Scenario: Mode changes during active playback
- **WHEN** the user changes mode while a response is already playing
- **THEN** the response SHALL continue on its acquired endpoint without redirection
- **AND** the next admitted response SHALL resolve the newly selected mode

### Requirement: Rejected PTT during playback does not apply actuator home-mode transition
Actuator auto-transition SHALL occur only after the half-duplex host accepts PTT admission. A PTT press rejected because response playback is active SHALL leave mode and selection provenance unchanged.

#### Scenario: Phone PTT is rejected during Work playback
- **WHEN** Work is selected, response playback is active, and phone PTT is pressed
- **THEN** the host SHALL reject PTT without transitioning to `OnAPinch`
- **AND** Work SHALL remain the selected mode
### Requirement: On-the-road car tile opens car configuration by long press
The On-the-road/CAR mode tile SHALL provide access to the dedicated car-device configuration view through long-press regardless of current On-the-road mode availability. This setup gesture SHALL remain separate from the tile's ordinary mode-selection click.

#### Scenario: User long-presses available CAR tile
- **WHEN** the dashboard is visible, On-the-road mode is available, and the user long-presses the CAR tile
- **THEN** the system SHALL open the car-device configuration view
- **AND** SHALL NOT change the selected input mode as a result of the long-press

#### Scenario: User long-presses unavailable CAR tile
- **WHEN** the dashboard is visible, On-the-road mode is unavailable, and the user long-presses the CAR tile
- **THEN** the system SHALL open the car-device configuration view
- **AND** SHALL NOT transition to On-the-road mode

#### Scenario: Car setup long-press does not dispatch tile tap
- **WHEN** a CAR-tile long-press is recognized
- **THEN** the system SHALL dispatch only the car-configuration navigation action
- **AND** SHALL NOT also dispatch the ordinary CAR-tile selection action
