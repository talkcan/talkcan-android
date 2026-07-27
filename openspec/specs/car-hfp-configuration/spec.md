## Purpose

TBD. Defines the car-device configuration capability: discovery, user selection, persistence, and runtime resolution of one exact HFP car identity, plus privacy-preserving diagnostics.

## Requirements

### Requirement: Car configuration lists live eligible HFP devices
The system SHALL provide a dedicated car-device configuration view that lists each exact `BluetoothDevice` currently exposed by the HEADSET profile and reporting `BluetoothProfile.STATE_CONNECTED`. The view SHALL use readable device labels only for presentation, SHALL keep duplicate or missing labels as distinct identities, and SHALL exclude the exact target RSM whenever that identity is known.

#### Scenario: Multiple connected HFP devices are shown separately
- **WHEN** the car-device configuration view opens with multiple connected HFP devices that are not the target RSM
- **THEN** the system SHALL show one selectable row for each exact device
- **AND** rows with equal or unavailable display names SHALL remain independently selectable

#### Scenario: Known RSM is not eligible as the car
- **WHEN** the exact target RSM and another HFP device are connected
- **THEN** the system SHALL omit or disable the target RSM as a car candidate
- **AND** SHALL allow the other connected device to be selected

#### Scenario: Stale profile entry is not selectable
- **WHEN** a device appears in the HEADSET profile device list but does not report `STATE_CONNECTED`
- **THEN** the system SHALL NOT present that device as an eligible car candidate

#### Scenario: No eligible device is connected
- **WHEN** no eligible HFP device is currently connected
- **THEN** the view SHALL show an explicit no connected-car-candidates state
- **AND** SHALL retain any previously recorded car configuration

### Requirement: User selection records one exact car identity
The system SHALL allow the user to select exactly one eligible connected HFP device as the car, SHALL revalidate that device against a fresh live profile snapshot before committing, and SHALL atomically replace any previous car configuration with the selected exact identity. Display names, list order, Bluetooth class, UUIDs, and candidate cardinality SHALL NOT determine the recorded identity.

#### Scenario: Connected candidate is selected
- **WHEN** the user selects an eligible connected HFP candidate
- **AND** the same exact device remains connected and is not the known target RSM at commit time
- **THEN** the system SHALL persist that exact device as the configured car
- **AND** the view SHALL identify it as selected

#### Scenario: User replaces the configured car
- **WHEN** one car is already configured
- **AND** the user selects a different eligible connected HFP device
- **THEN** the system SHALL atomically replace the previous identity with the new exact identity
- **AND** future On-the-road operations SHALL resolve only the new identity

#### Scenario: Candidate becomes unavailable before commit
- **WHEN** the user selects a displayed candidate
- **AND** that exact device is absent, disconnected, or proven to be the target RSM during commit revalidation
- **THEN** the system SHALL reject the mutation with recoverable feedback
- **AND** SHALL preserve the previously recorded car configuration unchanged

### Requirement: Car configuration survives restarts and reports live status
The system SHALL persist one configured car identity in application-private storage across application and foreground-service restarts. It SHALL separately report whether that configured identity is currently resolved as a connected HFP device, and SHALL NOT clear the identity because of a transient disconnect, Bluetooth disablement, permission loss, or profile-proxy loss.

#### Scenario: Configured car survives restart
- **WHEN** the application or foreground service restarts after a car has been selected
- **THEN** the same exact car identity SHALL remain configured
- **AND** its connected status SHALL be recomputed from the current HEADSET profile state

#### Scenario: Configured car disconnects
- **WHEN** the configured car is no longer present as a connected HFP device
- **THEN** the view SHALL show the recorded car as configured but unavailable
- **AND** SHALL retain the configuration for a later reconnect or explicit replacement

#### Scenario: Configured car reconnects
- **WHEN** the recorded car identity later appears as a connected HFP device
- **THEN** the view SHALL report the configured car as connected without requiring reselection

#### Scenario: Bluetooth inspection is unavailable
- **WHEN** `BLUETOOTH_CONNECT` permission is unavailable or the HEADSET profile proxy is unavailable
- **THEN** the view SHALL distinguish inspection unavailability from a legitimate empty connected-device list
- **AND** SHALL preserve the recorded configuration

### Requirement: Car configuration diagnostics do not expose hardware identity
The system SHALL make configuration discovery, selection, persistence, and runtime resolution observable using semantic state and outcome categories without logging full or partial Bluetooth hardware addresses. Any device label included for diagnosis SHALL remain non-authoritative for identity or routing.

#### Scenario: Configured resolution fails
- **WHEN** a configured car cannot be resolved for display or runtime acquisition
- **THEN** diagnostics SHALL record a semantic failure category and relevant non-identifying state
- **AND** SHALL NOT include the configured Bluetooth address