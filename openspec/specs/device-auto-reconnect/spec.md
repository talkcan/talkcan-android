## Purpose

Defines automatic reconnect behavior for the bonded `B02PTT-FF01` SPP serial control channel after unexpected serial loss.

## Requirements

### Requirement: Eligible service startup initiates the first serial connection
The system SHALL establish serial monitoring intent when the device-link service starts, SHALL establish started foreground-service ownership before automatic monitoring work depends on surviving the activity binding, and SHALL schedule an immediate first SPP serial connection when required permissions are granted, Android Bluetooth is enabled, and the bonded `B02PTT-FF01` target is available. Eligibility SHALL NOT depend on any prior manual or successful SPP session. Removing the activity binding because the app is backgrounded or the phone is locked SHALL NOT stop an active started device-link service.

#### Scenario: First launch with bonded target available
- **WHEN** the device-link service starts with required permissions granted, Bluetooth enabled, and the target RSM bonded
- **AND** no serial session has previously connected
- **THEN** the system SHALL establish monitoring intent
- **AND** establish started foreground-service ownership
- **AND** schedule an immediate SPP connection attempt
- **AND** process hardware button events after that connection succeeds

#### Scenario: Startup prerequisites are incomplete
- **WHEN** the device-link service starts while permissions are missing, Bluetooth is disabled, or no bonded target is available
- **THEN** the system SHALL NOT start an ineligible SPP attempt
- **AND** SHALL retain startup monitoring intent while the started foreground service remains active
- **AND** a later readiness refresh SHALL schedule the first attempt when all prerequisites become available

#### Scenario: App is backgrounded after automatic startup
- **WHEN** automatic monitoring has started without the user first requesting manual serial connection
- **AND** the activity loses visibility and removes its service binding
- **THEN** the started foreground device-link service SHALL remain active
- **AND** the foreground notification SHALL remain present
- **AND** active SPP monitoring or eligible reconnect work SHALL continue
- **AND** subsequent RSM hardware button events SHALL continue to be processed

#### Scenario: Phone locks after automatic startup
- **WHEN** automatic monitoring has started without the user first requesting manual serial connection
- **AND** locking the phone stops the activity and removes its service binding
- **THEN** the started foreground device-link service SHALL remain active
- **AND** the foreground notification SHALL remain present
- **AND** active SPP monitoring or eligible reconnect work SHALL continue
- **AND** subsequent RSM hardware button events SHALL continue to be processed while the process remains alive

#### Scenario: Explicit disconnect suppresses startup monitoring for the current service lifetime
- **WHEN** the user explicitly disconnects serial monitoring after automatic startup monitoring was established
- **THEN** the system SHALL clear monitoring intent
- **AND** cancel active or scheduled serial attempts
- **AND** remove started foreground-service ownership
- **AND** subsequent readiness refreshes in that service lifetime SHALL NOT re-establish monitoring intent

#### Scenario: Later service start restores automatic startup behavior
- **WHEN** a prior service lifetime ended after explicit disconnect and a new device-link service instance starts with all prerequisites available
- **THEN** the new service instance SHALL establish startup monitoring intent
- **AND** establish started foreground-service ownership
- **AND** schedule an immediate first attempt for that service lifetime

#### Scenario: Manual connect remains an explicit recovery action
- **WHEN** the user requests manual serial connection while no SPP session is active
- **THEN** the system SHALL establish monitoring intent
- **AND** establish or retain started foreground-service ownership
- **AND** use the same serialized prerequisite-gated connection scheduler as automatic startup
- **AND** SHALL NOT create a concurrent connection attempt

### Requirement: Unexpected serial loss triggers automatic reconnect
The system SHALL automatically attempt to reconnect the SPP serial control channel to the bonded `B02PTT-FF01` after serial monitoring ends unexpectedly while monitoring is still user-requested.

#### Scenario: SPP session ends without explicit disconnect
- **WHEN** serial monitoring has been started by the user and the active SPP event stream ends without the user requesting disconnect
- **THEN** the system keeps monitoring intent active
- **AND** the system schedules an automatic SPP reconnect attempt for the bonded target device
- **AND** the system does not stop the foreground device-link service solely because of that serial loss

#### Scenario: Reconnect succeeds
- **WHEN** an automatic reconnect attempt succeeds
- **THEN** the serial control channel becomes connected again
- **AND** subsequent hardware button events from the device are processed by the existing button parser and monitor state machine

### Requirement: Explicit disconnect stops automatic reconnect
The system SHALL treat the user's disconnect action as cancellation of serial monitoring intent and MUST NOT automatically reconnect after that action.

#### Scenario: User disconnects during an active SPP session
- **WHEN** the user requests serial disconnect while SPP is connected
- **THEN** the system closes the active serial connection
- **AND** the system clears monitoring intent
- **AND** the system cancels any pending automatic reconnect work
- **AND** the system removes foreground device-link ownership

#### Scenario: User disconnects while reconnect is pending
- **WHEN** the user requests serial disconnect while an automatic reconnect attempt is pending or in progress
- **THEN** the system cancels pending and in-progress automatic reconnect work
- **AND** the system MUST NOT start another reconnect attempt until the user requests serial connection again

### Requirement: Reconnect respects connection prerequisites
The system SHALL attempt automatic serial reconnect only when required permissions are granted, Android Bluetooth is enabled, and the target device is bonded. While serial monitoring intent remains active, a temporary prerequisite failure SHALL NOT remove started foreground-service ownership, and the system SHALL continue readiness refresh so recovery can resume automatic connection without reopening the app.

#### Scenario: Permissions are missing
- **WHEN** automatic reconnect is evaluated and required permissions are missing
- **THEN** the system SHALL NOT start an SPP reconnect attempt
- **AND** the system SHALL report the connection as not ready
- **AND** the started foreground service SHALL remain active while monitoring intent remains active
- **AND** a later readiness refresh SHALL resume eligible automatic connection after permission recovery

#### Scenario: Bluetooth is disabled
- **WHEN** automatic reconnect is evaluated and Android Bluetooth is disabled
- **THEN** the system SHALL NOT start an SPP reconnect attempt
- **AND** the system SHALL report the connection as not ready
- **AND** the started foreground service SHALL remain active while monitoring intent remains active
- **AND** a later readiness refresh SHALL resume eligible automatic connection after Bluetooth is enabled

#### Scenario: Target is not bonded or temporarily unavailable
- **WHEN** automatic reconnect is evaluated and no eligible `B02PTT-FF01` target is available
- **THEN** the system SHALL NOT start an SPP reconnect attempt
- **AND** the system SHALL report the target device as unavailable or not found
- **AND** the started foreground service SHALL remain active while monitoring intent remains active
- **AND** a later readiness refresh SHALL resume eligible automatic connection after the target becomes available

#### Scenario: Monitoring was explicitly stopped
- **WHEN** reconnect prerequisites are blocked after the user explicitly disconnected serial monitoring
- **THEN** the system SHALL NOT retain foreground-service ownership for recovery
- **AND** readiness refresh SHALL NOT re-establish monitoring intent

### Requirement: Reconnect attempts are serialized and delayed
The system SHALL avoid concurrent SPP reconnect attempts and SHALL wait between failed reconnect attempts while monitoring intent remains active.

#### Scenario: Reconnect already in progress
- **WHEN** an automatic reconnect attempt is already in progress
- **THEN** the system does not start a second concurrent SPP reconnect attempt

#### Scenario: Reconnect attempt fails while still eligible
- **WHEN** an automatic reconnect attempt fails and monitoring intent remains active with all reconnect prerequisites satisfied
- **THEN** the system schedules another reconnect attempt after a delay
- **AND** the system does not retry in a tight loop

### Requirement: Existing manual setup remains available
The system SHALL preserve the existing manual setup and fallback actions for permissions, Bluetooth settings, scanning, pairing, readiness retry, and manual serial connection.

#### Scenario: Device has not been paired
- **WHEN** the target device is not bonded
- **THEN** the user can still use the existing scan and pair controls to prepare the device
- **AND** automatic serial reconnect does not replace the pairing flow

#### Scenario: Manual connect after cancellation
- **WHEN** automatic reconnect has been cancelled by explicit disconnect
- **THEN** the user can request serial connection manually again
- **AND** a new monitoring intent is established for that session

### Requirement: Disconnected readiness is periodically refreshed
The system SHALL periodically perform the same readiness checks as the manual `Retry readiness checks` action while the foreground device-link service is active and the aggregate device readiness gate is false.

#### Scenario: Device is not ready while service is active
- **WHEN** the foreground device-link service is active
- **AND** the aggregate device readiness gate is false
- **THEN** the system periodically refreshes readiness using the same checks as the manual `Retry readiness checks` action
- **AND** the refresh checks required permissions, Android Bluetooth enabled state, bonded target availability, and Bluetooth SCO headset availability

#### Scenario: Periodic refresh observes recovered readiness
- **WHEN** periodic readiness refresh is active
- **AND** a later refresh finds permissions granted, Android Bluetooth enabled, the target device bonded, SPP connected, and Bluetooth SCO headset availability restored
- **THEN** the aggregate device readiness gate becomes true without requiring the user to tap `Retry readiness checks`

#### Scenario: Device becomes ready
- **WHEN** periodic readiness refresh is active
- **AND** the aggregate device readiness gate becomes true
- **THEN** the system stops periodic readiness refresh work

#### Scenario: User explicitly disconnects serial monitoring
- **WHEN** periodic readiness refresh is active
- **AND** the user requests serial disconnect
- **THEN** the system stops periodic readiness refresh work
- **AND** the system does not use periodic readiness refresh to re-establish monitoring intent

#### Scenario: Service is destroyed
- **WHEN** periodic readiness refresh is active
- **AND** the device-link service is destroyed
- **THEN** the system cancels periodic readiness refresh work

### Requirement: Periodic readiness refresh preserves manual fallback behavior
The system SHALL keep the existing manual `Retry readiness checks` action available and SHALL NOT replace manual scan, pair, Bluetooth settings, or serial connection actions.

#### Scenario: Manual readiness retry remains available
- **WHEN** the Device Link screen is visible
- **THEN** the user can still tap `Retry readiness checks` to run readiness checks immediately

#### Scenario: Periodic refresh does not initiate setup actions
- **WHEN** periodic readiness refresh runs while the target device is not ready
- **THEN** the system SHALL NOT start Bluetooth discovery
- **AND** the system SHALL NOT initiate pairing
- **AND** the system SHALL NOT open Android Bluetooth settings
- **AND** the system SHALL NOT initiate a new manual serial connection

### Requirement: RSM serial lifecycle preserves unrelated PTT sessions
Automatic reconnect, failed connection, stream loss, and explicit disconnect of the RSM SPP control channel SHALL affect only RSM-owned PTT input. These events SHALL NOT cancel, release, abort, or change the presentation of a pending or active Phone or CarTelecom session. Eligible automatic reconnect scheduling SHALL continue under the existing serialized retry policy while unrelated PTT input is pending or active.

#### Scenario: Failed automatic reconnect during Phone capture
- **WHEN** an automatic RSM SPP reconnect attempt fails while a Phone session is pending or capturing
- **THEN** the Phone session SHALL remain owned and active
- **AND** reconnect policy SHALL schedule later eligible attempts without terminating Phone input

#### Scenario: Failed automatic reconnect during pending car route acquisition
- **WHEN** an automatic RSM SPP reconnect attempt fails while a CarTelecom session is waiting for its exact car route
- **THEN** the CarTelecom session SHALL continue waiting until its own route, timeout, user, or Telecom lifecycle terminates it
- **AND** the failed RSM attempt SHALL NOT abort the Telecom connection

#### Scenario: Failed automatic reconnect during active car capture
- **WHEN** an automatic RSM SPP reconnect attempt fails while a CarTelecom capture is active
- **THEN** the car capture and Telecom connection SHALL remain active
- **AND** reconnect policy SHALL remain eligible to retry later

#### Scenario: Active RSM serial session ends
- **WHEN** an RSM-owned PTT session is pending or active
- **AND** its owning SPP serial event stream ends
- **THEN** the system SHALL cancel that RSM-owned session through source-scoped cancellation
- **AND** SHALL retain existing automatic reconnect scheduling behavior

#### Scenario: Explicit serial disconnect during unrelated capture
- **WHEN** the user explicitly disconnects RSM serial monitoring while a Phone or CarTelecom session is pending or active
- **THEN** the system SHALL stop RSM monitoring and reconnect work according to the existing disconnect contract
- **AND** SHALL preserve the unrelated PTT session

#### Scenario: Explicit serial disconnect during RSM capture
- **WHEN** the user explicitly disconnects RSM serial monitoring while an RSM-owned capture is active
- **THEN** the system SHALL cancel the RSM-owned capture exactly once
- **AND** SHALL stop monitoring and reconnect work according to the existing disconnect contract
