## Purpose

Defines the decomposed `PttForegroundService` architecture where the Android `Service` shell retains its lifecycle, binder surface, and composition-root responsibilities while each internal concern is owned by an extracted collaborator with exclusive mutable state, single-path side effects, and preservation of observable temporal behavior.

## Requirements

### Requirement: Stable Android service shell
The system SHALL retain `PttForegroundService` as the Android `Service`, binder target, top-level composition root, and explicit owner of process service startup and shutdown while its internal responsibilities are decomposed.

#### Scenario: Existing binder caller uses a migrated operation
- **WHEN** a binder or UI caller invokes an existing `PttForegroundService` property or method whose implementation has moved to a collaborator
- **THEN** the caller receives the same type, result, state, and side effects through the existing service surface

#### Scenario: Android starts and destroys the decomposed service
- **WHEN** Android invokes service creation, start-command, binding, or destruction callbacks
- **THEN** `PttForegroundService` performs the same Android lifecycle obligations and preserves the existing top-level construction and teardown order

### Requirement: Exclusive mutable ownership
Every migrated mutable state field, resource reference, and coroutine job SHALL have exactly one authoritative owner after its responsibility is cut over.

#### Scenario: Responsibility migration completes
- **WHEN** a responsibility is delegated to an extracted collaborator
- **THEN** the corresponding service-local mutable state and job ownership are removed rather than mirrored between the service and collaborator

#### Scenario: Collaborator publishes service state
- **WHEN** an extracted collaborator observes or produces a domain transition
- **THEN** it emits a typed event or invokes a narrow callback instead of mutating service fields or a shared mutable `AppState` directly

### Requirement: Single-path side effects
The system SHALL execute exactly one production path for each audio, Bluetooth, Telecom, notification, filesystem, native-engine, channel, and state-publication side effect during migration.

#### Scenario: Extracted path becomes active
- **WHEN** a service operation delegates to an extracted collaborator
- **THEN** the legacy service implementation does not execute the same side effect in parallel

#### Scenario: Differential verification is used
- **WHEN** old and extracted behavior are compared during tests
- **THEN** deterministic functions may be evaluated against the same input, while side-effectful implementations run separately against recording fakes

### Requirement: Channel-management behavior preservation
The extracted channel-management responsibility SHALL preserve provider resolution, definition construction, repository mutation, successful-selection notifications, typed failures, and diagnostic ordering.

#### Scenario: Channel is created with an explicit payload
- **WHEN** a caller creates a channel using an available provider and supplies configuration payload
- **THEN** the repository receives a new enabled definition with a generated ID, the provider's current schema version, and the supplied payload

#### Scenario: Channel is created without a payload
- **WHEN** a caller creates a channel using an available provider without configuration payload
- **THEN** the repository receives the provider's default payload and current schema version

#### Scenario: Provider is unavailable
- **WHEN** channel creation or configuration update resolves to a missing provider
- **THEN** the caller receives the same provider-migration failure classification and no channel mutation is committed

#### Scenario: Channel selection succeeds
- **WHEN** the repository accepts a channel selection
- **THEN** immediate playback and deferred-audio playback are notified once in their existing order and the existing selection diagnostic is emitted after those notifications

#### Scenario: Channel selection fails
- **WHEN** the repository rejects a channel selection
- **THEN** neither playback coordinator is notified and the caller receives the same unsuccessful result

### Requirement: Authoritative service state projection
The decomposed service SHALL expose the same `AppState`, channel descriptors, bootstrap state, capture state, audio level, profile state, and channel-browse projections, with one authoritative mutation owner for `AppState`.

#### Scenario: Compound readiness transition occurs
- **WHEN** connection readiness changes input-mode availability, monitor readiness, or car-media state
- **THEN** observers receive the same ordered state values as before decomposition

#### Scenario: Runtime catalogue projection changes
- **WHEN** catalogue membership, active channel, runtime readiness, pending count, or descriptor availability changes
- **THEN** the dashboard and car-browse projections retain the same membership, order, active identity, status, and pending values

#### Scenario: UI mutator is invoked
- **WHEN** a caller updates monitor configuration, input mode, or car-HFP selection through the service API
- **THEN** the authoritative state owner publishes the same resulting state without another component independently writing the flow

### Requirement: Serial and reconnect behavior preservation
The extracted serial/reconnect responsibility SHALL preserve manual connection, automatic reconnection, device targeting, SPP state collection, cancellation classification, delay scheduling, and service-retention decisions.

#### Scenario: Manual serial connection starts
- **WHEN** the caller requests serial connection and no serial job is active
- **THEN** monitoring, device resolution, connection state, SPP collection, and foreground behavior occur in the same order as before decomposition

#### Scenario: Automatic reconnect is eligible
- **WHEN** monitoring is requested, prerequisites are satisfied, no connection attempt is active, and reconnect policy schedules an attempt
- **THEN** exactly one reconnect job is scheduled for the same deadline and starts the same target-device connection path

#### Scenario: Reconnect is blocked or cancelled
- **WHEN** prerequisites fail, monitoring stops, explicit disconnect occurs, or another attempt owns the connection
- **THEN** the same reconnect disposition, PTT cancellation caller, pending jobs, and service-retention outcome are produced

#### Scenario: Serial session ends during PTT
- **WHEN** the RSM serial session ends while an RSM PTT session is pending or active
- **THEN** cancellation, terminal cleanup, reconnect handling, and deferred service shutdown preserve their existing order and exactly-once behavior

### Requirement: Foreground-service and readiness-loop preservation
The decomposed foreground responsibility SHALL preserve notification identity and content, foreground start/stop idempotence, readiness polling, monitoring retention, and serial-disconnect shutdown gating.

#### Scenario: Monitoring requires foreground operation
- **WHEN** monitoring is requested and the service is not foreground
- **THEN** the existing notification channel and notification ID are used, foreground mode starts once, and the readiness refresh loop starts at the existing interval

#### Scenario: Foreground start is repeated
- **WHEN** multiple events request foreground operation while it is already active
- **THEN** the service does not duplicate foreground transition or readiness-loop ownership

#### Scenario: Serial disconnect may stop the service
- **WHEN** serial disconnect is pending, monitoring is no longer requested, and no PTT session remains active
- **THEN** the foreground notification is removed and the service stops according to the existing shutdown predicate

#### Scenario: Active work delays disconnect shutdown
- **WHEN** serial disconnect is pending but monitoring or a PTT terminal session still requires the service
- **THEN** foreground/service teardown is deferred until the existing retention conditions clear

### Requirement: Core initialization and resource ownership preservation
The extracted core initializer SHALL preserve bootstrap construction results, model polling, controller availability, navigation-TTS preparation, retry discard, and native-resource shutdown behavior.

#### Scenario: Bootstrap constructs core controllers
- **WHEN** `BootstrapCoordinator` requests STT, TTS, journal, text-output, or navigation-TTS preparation
- **THEN** the same resources, model paths, capability adapters, result classifications, and state transitions are produced

#### Scenario: Bootstrap attempt is discarded
- **WHEN** retry or prerequisite recovery discards the current controllers
- **THEN** model pollers and owned controllers are cancelled or shut down once in the same order before replacement construction begins

#### Scenario: Service is destroyed after partial initialization
- **WHEN** service teardown occurs with only a subset of core resources initialized
- **THEN** every initialized resource is released once, uninitialized resources are ignored safely, and teardown completes with the same timeout behavior

### Requirement: Navigation announcement behavior preservation
The extracted announcement responsibility SHALL preserve vocabulary resolution, navigation synthesis, error-beep fallback, host-audio playback, result reporting, and latest-wins behavior.

#### Scenario: RSM channel announcement is requested
- **WHEN** a valid menu or channel announcement key is received
- **THEN** the same text is resolved from the current catalogue and submitted to the existing navigation engine once

#### Scenario: Announcement key is invalid
- **WHEN** the key does not identify a supported menu or current channel
- **THEN** no synthesis or playback request is started

#### Scenario: Error feedback is requested
- **WHEN** RSM error feedback requires a beep and navigation audio is available
- **THEN** the same PCM feedback follows the existing navigation/audio ownership path and bootstrap receives the same synthesis result classification

### Requirement: Temporal and cleanup equivalence
The decomposition SHALL preserve callback ordering, coroutine topology, timeout values, cancellation propagation, and exactly-once cleanup wherever those properties are externally observable or protect resource ownership.

#### Scenario: Coroutine-owned responsibility moves
- **WHEN** a job or suspending operation is transferred to a collaborator
- **THEN** its dispatcher, parent or detached-job relationship, timeout, cancellation behavior, and shutdown await behavior remain equivalent

#### Scenario: Multiple terminal signals race
- **WHEN** disconnect, service teardown, route timeout, release, or cancellation signals overlap
- **THEN** the existing ownership decision wins and each route, target, capture, TTS instance, Bluetooth resource, notification, and completion callback is released or published at most once

#### Scenario: Late callback arrives
- **WHEN** a Bluetooth, Telecom, native-engine, runtime, or coroutine callback arrives after its owner has been retired
- **THEN** it cannot revive retired state, duplicate a side effect, or overwrite the current authoritative state

### Requirement: Incremental removal gate
A migrated service responsibility SHALL be considered complete only after all intended callers use the extracted owner, focused equivalence verification passes, and obsolete service-local implementation is removed.

#### Scenario: Extracted collaborator is introduced
- **WHEN** the collaborator exists but some callers or mutable ownership remain on the legacy path
- **THEN** the responsibility remains incomplete and the duplicate path is not retained as a delivered compatibility layer

#### Scenario: Responsibility cutover is complete
- **WHEN** all callers use the extracted boundary and focused verification establishes equivalent behavior
- **THEN** obsolete methods not required by the stable binder/API surface, fields, imports, and delegation scaffolding are removed

### Requirement: End-to-end regression barrier
The completed decomposition SHALL pass focused automated verification and the established device-level acceptance flow before being treated as behaviorally equivalent.

#### Scenario: Automated verification runs
- **WHEN** the final decomposed service is verified
- **THEN** focused channel, state projection, reconnect, input-mode, bootstrap, PTT/audio, runtime integration, service lifecycle, and Kotlin compilation checks pass

#### Scenario: Physical-device acceptance runs
- **WHEN** the debug build is installed on the target Android device and exercised with `B02PTT-FF01`
- **THEN** RSM PTT/buttons, mode transitions, SCO recording/playback, Telecom car routing, background operation, foreground notification retention, and disconnect notification removal behave as before decomposition
