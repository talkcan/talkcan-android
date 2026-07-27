## Purpose

Defines how the active `InputMode` auto-switches to `OnTheRoad` when Android Auto becomes present and reverts to `Work` when Android Auto disconnects, while preserving explicit user selections through a `selectedBy: User | System` invariant. The `InputModeController` consults the invariant on every Android Auto presence transition so auto-switch never clobbers an intentional user choice.

## Requirements

### Requirement: InputMode auto-switches on Android Auto presence
The system SHALL switch the active `InputMode` to `OnTheRoad` when Android Auto becomes present and back to `Work` when Android Auto disconnects, while respecting explicit user selections.

#### Scenario: Auto switches to OnTheRoad on Android Auto connect
- **WHEN** Android Auto presence transitions to connected
- **AND** the `OnTheRoad` input mode is available per `InputModeAvailability`
- **AND** the current active mode was previously selected by the system (auto-switch)
- **OR** the current active mode is `OnAPinch`
- **THEN** the system SHALL set the active input mode to `OnTheRoad`

#### Scenario: Auto reverts to Work on Android Auto disconnect
- **WHEN** Android Auto presence transitions to disconnected
- **AND** the `Work` input mode is available per `InputModeAvailability`
- **AND** the current active mode was previously auto-switched to `OnTheRoad` by the system
- **THEN** the system SHALL set the active input mode to `Work`

#### Scenario: Manual user selection is preserved across auto transitions
- **WHEN** the user has explicitly selected an `InputMode`
- **AND** Android Auto presence transitions to connected or disconnected
- **THEN** the system SHALL NOT override the user-selected mode
- **AND** the user-selected mode SHALL remain active until the user makes a new explicit selection

#### Scenario: Tracking who selected the InputMode
- **WHEN** the user or the system sets the active `InputMode`
- **THEN** the system SHALL record whether the selection was made by the user or by the system auto-switch
- **AND** the auto-switch decision SHALL consult this record before overriding

#### Scenario: OnTheRoad unavailable
- **WHEN** Android Auto presence transitions to connected
- **AND** the `OnTheRoad` input mode is not available per `InputModeAvailability`
- **THEN** the system SHALL NOT auto-switch the input mode
- **AND** the current `InputMode` SHALL remain unchanged