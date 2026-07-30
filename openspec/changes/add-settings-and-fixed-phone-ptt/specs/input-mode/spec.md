## MODIFIED Requirements

### Requirement: User mode selection
The system SHALL allow the user to select any available mode from the Radio dashboard as the preferred audio device for later admitted channel-content playback. Selecting a mode SHALL NOT claim exclusive PTT ownership: an admitted RSM, phone, or car PTT actuator SHALL still auto-transition to its own home mode. Device setup and monitoring SHALL be explicit Settings actions rather than alternate gestures on selector tiles.

#### Scenario: User selects an available audio device
- **WHEN** the user taps an available device in the audio-device selector
- **THEN** the system SHALL transition to that mode
- **AND** later admitted channel-content playback SHALL use that mode's audio-route policy
- **AND** the system SHALL NOT navigate to device setup

#### Scenario: User taps an unavailable device
- **WHEN** the user taps an unavailable audio-device tile
- **THEN** the system SHALL not transition to that mode
- **AND** the tile SHALL continue to expose its unavailable state
- **AND** device setup SHALL remain reachable through Settings without a hidden tile gesture

#### Scenario: PTT actuator overrides manual selection
- **WHEN** the user manually selects one available audio device
- **AND** a PTT actuator belonging to another available device is subsequently admitted
- **THEN** the system SHALL transition to the actuator's home mode before route acquisition
- **AND** the dashboard SHALL update the selected audio device

#### Scenario: Device event rules apply after user selection
- **WHEN** the user selects a mode and a subsequent device event satisfies an automatic transition rule
- **THEN** the applicable transition rule SHALL execute according to selection provenance and availability

### Requirement: PTT routing respects readiness
The system SHALL evaluate the active channel's readiness state before dispatching a PTT capture, regardless of which selected audio device or actuator initiated PTT. Route resolution SHALL be based on the active `InputMode` after actuator auto-transition succeeds, and each mode's route acquisition SHALL prove ownership of its semantic endpoint before capture begins. Phone PTT SHALL originate only from the fixed phone PTT control and SHALL target the already active channel.

#### Scenario: PTT on a ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL acquire the Work audio route by proving target RSM HFP ownership
- **AND** resolve the audio route for `Work` mode using the target RSM-owned SCO transport
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnTheRoad` mode using the Telecom self-call car SCO path
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnAPinch` mode using the local phone route
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: Fixed phone PTT on a ready active channel
- **WHEN** the fixed phone PTT control is pressed and the active channel's `isReady` state is true
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** preserve the active-channel selection
- **AND** resolve the audio route for `OnAPinch` mode
- **AND** dispatch the capture to the active channel's designated controller

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

#### Scenario: Android Auto play-pause auto-transitions to On-the-road mode
- **WHEN** the Android Auto play-pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad` mode
- **AND** resolve the audio route for `OnTheRoad` mode
- **AND** dispatch the capture to the active channel's designated controller

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved home-mode audio route if PTT is pressed while the active channel is not ready and that home-mode route can be safely acquired. The system SHALL NOT play error feedback through a different mode's endpoint when actuator auto-transition or route acquisition fails.

#### Scenario: PTT on a not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is false
- **AND** target RSM HFP ownership of the Work SCO route can be proven
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Work not-ready beep skipped when RSM route ownership cannot be proven
- **WHEN** PTT is pressed for the Work actuator path
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
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch local phone route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Fixed phone PTT targets a not-ready active channel
- **WHEN** the fixed phone PTT control is pressed and the active channel's `isReady` state is false
- **THEN** the system SHALL transition to `OnAPinch` only after admission permits the actuator transition
- **AND** preserve the active-channel selection
- **AND** play a two-tone error beep on the On-a-pinch route when safely acquired
- **AND** drop the PTT capture without routing to a controller

### Requirement: Actuator auto-transition
The system SHALL automatically transition to the home mode of any admitted actuator when that home mode is available, and then dispatch PTT using that mode's route-acquisition rules. If the home mode is unavailable or route acquisition fails, the system SHALL fail closed without routing capture or feedback through a different endpoint.

#### Scenario: RSM PTT pressed from any mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **AND** half-duplex admission accepts the press
- **THEN** the system SHALL transition to `Work`
- **AND** dispatch PTT in `Work` mode
- **AND** Work route acquisition SHALL prove target RSM HFP ownership before capture or playback begins

#### Scenario: RSM PTT acquisition fails
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **BUT** target RSM HFP route acquisition fails
- **THEN** the system SHALL NOT start capture
- **AND** the system SHALL NOT use the OnTheRoad car route as fallback
- **AND** the system SHALL NOT play transition-failure feedback through the car route

#### Scenario: Fixed phone PTT pressed from any mode
- **WHEN** the fixed phone PTT control is pressed while in any mode
- **AND** half-duplex admission accepts the press
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** dispatch PTT in `OnAPinch` mode to the active channel

#### Scenario: Android Auto play-pause pressed from any mode
- **WHEN** the Android Auto play-pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **AND** half-duplex admission accepts the press
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** dispatch PTT in `OnTheRoad` mode

#### Scenario: Actuator pressed for unavailable mode
- **WHEN** an actuator is pressed but its home mode is not available
- **THEN** the system SHALL not transition
- **AND** the system SHALL NOT resolve capture or feedback through a different mode's endpoint

### Requirement: Visible mode selector on main dashboard
The system SHALL display an audio-device selector on the Radio dashboard showing Radio, Car, and Phone with icon-first availability indicators. The selector SHALL describe the selected device as the destination for later admitted replies and SHALL explain that using another device's PTT switches selection automatically. Each device SHALL remain visible even when unavailable.

#### Scenario: All audio devices are visible
- **WHEN** the Radio dashboard is displayed
- **THEN** the selector SHALL show Radio, Car, and Phone controls
- **AND** each control SHALL include a device icon, concise label, and availability state

#### Scenario: Unavailable device is shown
- **WHEN** a device mode is unavailable
- **THEN** its selector control SHALL remain visible
- **AND** its unavailable state SHALL be communicated through text or an indicator rather than color alone
- **AND** it SHALL NOT expose a hidden setup gesture

#### Scenario: Selected playback device is highlighted
- **WHEN** a mode is selected
- **THEN** its device control SHALL be visually and semantically selected
- **AND** the selector SHALL indicate that later admitted replies target that device

#### Scenario: Actuator auto-switch updates selector
- **WHEN** an admitted PTT actuator transitions to its home mode
- **THEN** the selector SHALL update to that device from the published mode state

## REMOVED Requirements

### Requirement: Work RSM tile opens setup by long press
**Reason**: Device tiles become explicit audio-device selectors; hidden setup gestures conflict with the first-class Settings hierarchy.

**Migration**: Open RSM setup or monitoring from Settings > Devices and audio > Radio.

### Requirement: On-the-road car tile opens car configuration by long press
**Reason**: Device tiles become explicit audio-device selectors; hidden setup gestures conflict with the first-class Settings hierarchy.

**Migration**: Open car configuration from Settings > Devices and audio > Car.
