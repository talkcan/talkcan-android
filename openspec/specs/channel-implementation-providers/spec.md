## Purpose

Defines implementation-neutral channel provider descriptors, construction,
configuration, availability, and host-capability boundaries.

## Requirements

### Requirement: Providers expose stable implementation descriptors
The host SHALL register channel implementations through descriptors keyed by a stable, non-blank implementation identifier that is independent of channel instance IDs, display names, catalogue position, and implementation class names. A descriptor SHALL declare presentation metadata, its current configuration schema version, default configuration production, configuration validation and migration, runtime construction, semantic capability eligibility, and generic preparation traits. Descriptor contracts SHALL use host-domain values and SHALL NOT expose Android, transport, hardware, or connection objects. The host SHALL NOT register any built-in channel provider; channel providers SHALL be supplied only as installed Lua packages.

#### Scenario: Multiple instances resolve one provider
- **WHEN** two catalogue definitions reference the same registered implementation identifier
- **THEN** the host SHALL resolve both definitions through the same provider descriptor
- **AND** it SHALL construct independent runtime instances keyed by their distinct channel instance IDs

#### Scenario: Descriptor is consumed without platform leakage
- **WHEN** core channel code reads a provider descriptor to create, configure, present, or prepare a channel instance
- **THEN** it SHALL receive only descriptor metadata, opaque configuration values, generic preparation traits, and host capability contracts
- **AND** it SHALL NOT receive an Android context, hardware handle, transport client, connection state machine, or reconnect policy

#### Scenario: Installed provider descriptor exposes configuration and capabilities
- **WHEN** an installed Lua provider descriptor is loaded from a validated package revision
- **THEN** its derived descriptor SHALL expose its validated configuration fields and semantic capability allowlist eligibility to the host
- **AND** it SHALL NOT execute Lua code during snapshot loading or descriptor construction

#### Scenario: Built-in providers are absent
- **WHEN** the registry is composed at service startup
- **THEN** no built-in provider descriptor SHALL be registered
- **AND** the implementation identifiers `builtin:journal`, `builtin:keyboard`, `builtin:openai-agent`, and `builtin:debug` SHALL remain unregistered

#### Scenario: Existing catalogue definitions for removed built-ins become unavailable
- **WHEN** a catalogue definition references implementation identifier `builtin:journal`, `builtin:keyboard`, `builtin:openai-agent`, or `builtin:debug`
- **THEN** resolution SHALL return an explicit unavailable-provider result for that definition
- **AND** the host SHALL NOT automatically redirect, copy configuration, or rebind it to an installed repository provider

### Requirement: Provider registration is deterministic
The provider registry SHALL contain at most one current descriptor for each implementation identifier. The host SHALL NOT register built-in providers, and the `builtin:` implementation-identifier prefix SHALL remain reserved and SHALL NOT be registrable by an installed package. Installed providers SHALL be supplied only as one complete immutable host-owned snapshot. Before replacing the installed snapshot, the registry SHALL validate that every installed identifier is canonical, unique, derived from its resolved durable provider identity, consistent across snapshot key, descriptor, and configuration provider, and noncolliding with every host-reserved identifier (including the reserved `builtin:` prefix). Any invalid installed entry SHALL reject the complete candidate snapshot with a typed validation result. Resolved descriptors SHALL remain fully operational across snapshot replacement.

#### Scenario: Installed provider collides with a reserved identifier
- **WHEN** a candidate installed snapshot contains an implementation identifier equal to a host-reserved identifier, including the `builtin:` prefix
- **THEN** replacement SHALL reject the complete candidate snapshot with a typed collision result
- **AND** the prior provider resolution snapshot SHALL remain unchanged

#### Scenario: Installed snapshot contains inconsistent identity
- **WHEN** an installed snapshot key, host-derived repository provider ID, descriptor implementation ID, or configuration-provider ID differs from the others
- **THEN** replacement SHALL reject the complete candidate snapshot
- **AND** no descriptor from that candidate snapshot SHALL become resolvable

#### Scenario: Valid installed snapshot replaces its predecessor
- **WHEN** a complete collision-free installed snapshot validates successfully
- **THEN** the registry SHALL publish it atomically
- **AND** concurrent resolution SHALL observe either the complete predecessor or complete successor map

#### Scenario: Unknown provider is resolved
- **WHEN** a catalogue definition references an implementation identifier absent from the current complete provider snapshot
- **THEN** resolution SHALL return an explicit unavailable-provider result for that definition
- **AND** the host SHALL NOT select another provider by display name, configuration shape, package version, catalogue position, or reserved-prefix kind
### Requirement: Providers own versioned configuration schemas
Each provider SHALL own the default payload, supported schema versions, validation rules, and forward migrations for its configuration. Before runtime construction or a configuration commit, the host SHALL ask the resolved provider to migrate the preserved payload to its current schema version and validate the migrated result. Provider schema processing SHALL be deterministic and SHALL NOT perform hardware, network, Android, or other externally visible effects.

#### Scenario: New instance requests default configuration
- **WHEN** the host creates a channel definition from a registered provider without user-supplied configuration
- **THEN** it SHALL obtain the default payload and schema version from that provider
- **AND** it SHALL validate the result through the same provider schema contract used for persisted payloads

#### Scenario: Older supported configuration is loaded
- **WHEN** a definition contains a provider-supported older schema version
- **THEN** the provider SHALL migrate the payload through its declared forward migration path
- **AND** the host SHALL validate the complete migrated payload before constructing a runtime or committing the migrated definition

#### Scenario: Configuration validation fails
- **WHEN** a provider rejects a proposed or migrated configuration payload
- **THEN** the host SHALL return a typed configuration error associated with the implementation identifier and schema version
- **AND** it SHALL NOT construct or update the runtime from that payload
- **AND** it SHALL NOT commit a partially migrated or partially validated payload

### Requirement: Runtime construction is instance scoped
A provider SHALL construct a runtime only from one channel instance's stable ID, effective definition metadata, validated current-version configuration, and an instance-scoped host capability acquisition boundary. The provider SHALL also receive an opaque host-owned generation execution context that supplies typed, generation-bound timer scheduling and background-task admission without exposing a Kotlin `CoroutineScope`, `ActorRuntime`, registry gate, Android object, or any other platform implementation type. This context SHALL be bound exclusively to the single generation being constructed. Admission after that generation is closed or replaced SHALL return a typed `CLOSED` rejection; bounded resource exhaustion SHALL return a distinct typed `CAPACITY_EXHAUSTED` rejection.

#### Scenario: Runtime is constructed for a valid definition
- **WHEN** a registered provider successfully migrates and validates one available channel definition
- **THEN** the host SHALL invoke that provider's runtime constructor with the definition's own instance ID, validated configuration, and an opaque generation execution context
- **AND** any host capabilities made available to the runtime SHALL be scoped to that instance

#### Scenario: Generation execution context is opaque and does not expose platform objects
- **WHEN** the provider inspects the generation execution context supplied to its runtime constructor
- **THEN** the context SHALL expose only lifecycle-continuation authority through typed operations
- **AND** it SHALL NOT expose a `CoroutineScope`, actor identity, registry gate, `lua_State*`, Android `Context`, coroutine reference, userdata, or any native or platform object

#### Scenario: Generation execution context rejects post-close admission
- **WHEN** the constructed runtime generation is closed or replaced and a provider attempts to schedule a timer or admit a task through the execution context
- **THEN** the context SHALL reject the admission with the typed reason `CLOSED`
- **AND** it SHALL NOT schedule work, invoke a callback, forward the operation to an actor or Lua state, or conflate closure with capacity exhaustion

#### Scenario: Accepted timer becomes stale after close
- **WHEN** a provider scheduled a timer while its generation was live and that generation closes before the timer callback is admitted
- **THEN** the host SHALL suppress the callback and dispose its generation-bound timer resources
- **AND** it SHALL NOT invoke the provider, actor, or Lua state

#### Scenario: Sibling configuration remains isolated
- **WHEN** a provider constructs or updates one of multiple instances using the same implementation identifier
- **THEN** it SHALL use only the addressed instance's configuration and capability scope
- **AND** each instance SHALL receive its own generation execution context
- **AND** it SHALL NOT read or mutate a sibling instance by implementation identifier or catalogue order

#### Scenario: Existing Kotlin provider constructs a runtime
- **WHEN** a built-in Kotlin provider constructs a runtime without using the opaque generation execution context
- **THEN** the host SHALL still supply a generation execution context even if the Kotlin provider does not consume it
- **AND** the existing behavior of the Kotlin provider SHALL remain unchanged
- **AND** the provider SHALL NOT be required to reference the context
### Requirement: Provider unavailability preserves channel instances
A definition whose provider is missing, incompatible, fails configuration migration or validation, or fails runtime construction SHALL remain a first-class catalogue instance and SHALL project an explicit unavailable state with a typed, actionable reason. Provider unavailability SHALL NOT discard or rewrite the preserved configuration payload, silently omit the instance from ordered projections, or prevent unrelated providers and instances from operating.

#### Scenario: Persisted provider is not installed
- **WHEN** the catalogue loads a valid definition whose implementation identifier has no registered provider
- **THEN** the host SHALL preserve the definition's instance ID, name, enabled state, order, schema version, and configuration payload unchanged
- **AND** it SHALL expose that instance as unavailable
- **AND** it SHALL refuse runtime preparation for that instance without starting capture

#### Scenario: Provider cannot consume the stored schema
- **WHEN** a registered provider does not support the definition's schema version or cannot migrate its payload
- **THEN** the host SHALL preserve the original definition unchanged
- **AND** it SHALL expose a typed incompatible-configuration reason rather than substituting defaults

#### Scenario: One provider fails to load
- **WHEN** one provider throws or returns failure during descriptor initialization or runtime construction
- **THEN** the affected definitions SHALL become unavailable with a normalized provider failure
- **AND** definitions resolved by other providers SHALL remain available and operational

### Requirement: Provider contracts remain implementation-neutral
The provider boundary SHALL support implementations supplied independently of the core channel model without requiring exhaustive built-in branches. Both built-in Kotlin and installed Lua providers SHALL share the same implementation descriptor, configuration schema, and runtime-construction boundary, and the host SHALL NOT maintain provider-specific branches for configuration presentation or capability resolution.

#### Scenario: Additional conforming provider is registered
- **WHEN** a new provider implements the descriptor, schema, runtime, and host-capability contracts
- **THEN** its instances SHALL participate in resolution, configuration, construction, readiness, and ordered projection through those generic contracts
- **AND** core routing and catalogue code SHALL NOT require an implementation-specific branch

#### Scenario: Installed Lua provider uses the generic provider boundary
- **WHEN** an installed Lua provider constructs its runtime generation
- **THEN** it SHALL interact with the host solely through the instance-scoped execution context and semantic capability adapters
- **AND** it SHALL NOT bypass the provider boundary or access internal platform APIs directly

#### Scenario: Platform hardening is deployed
- **WHEN** an installed Lua provider attempts to access host resources outside its declared capabilities
- **THEN** the host SHALL deny access and fail-closed
- **AND** the host SHALL NOT execute any Lua code or allocate resources for undeclared capabilities

### Requirement: Provider choices use stable semantic references
Provider configuration SHALL encode external host resources as stable identifiers and scalar host-domain values only. A provider SHALL be able to declare dynamic choice requirements for profiles, models, keyboard profiles, or other host capabilities while leaving resolution and lifecycle ownership to the host. Provider configuration and presentation contracts SHALL remain independent of Android UI, SDK, transport, and connection objects.

#### Scenario: Host choice is rendered
- **WHEN** a provider editor requests choices for a profile, model, or keyboard profile field
- **THEN** the host SHALL supply typed choice metadata and availability reasons through a provider-neutral contract
- **AND** the provider SHALL retain only the selected stable identifier or semantic scalar

#### Scenario: Choice resource changes
- **WHEN** a referenced host resource changes, is removed, or becomes unavailable
- **THEN** the provider SHALL expose an explicit typed configuration or readiness error for that instance
- **AND** it SHALL NOT retain or mutate the removed host object, connection, or UI state

### Requirement: Resolved provider revisions are explicit host-domain identity
Every resolved provider SHALL expose an opaque immutable provider-revision fingerprint used by runtime reconciliation. A built-in provider SHALL use a stable host-owned revision for its unchanged implementation composition. An installed Lua provider SHALL use the SHA-256 digest of its exact active artifact. Republishing semantically identical provider source SHALL retain the same fingerprint; activating, updating, or rolling back to different executable content SHALL produce a different fingerprint. Revision fingerprints SHALL NOT be written to channel definitions or exposed as Kotlin, Android, actor, filesystem, or package-store objects.

#### Scenario: Same installed artifact is republished
- **WHEN** provider composition republishes an installed provider with the same implementation ID and exact artifact digest
- **THEN** the resolved provider revision fingerprint SHALL remain equal
- **AND** consumers SHALL be able to classify the publication as the same provider revision

#### Scenario: Installed provider content changes
- **WHEN** update or rollback activates a different exact artifact digest for the same provider identity
- **THEN** the implementation identifier SHALL remain unchanged
- **AND** the resolved provider revision fingerprint SHALL change

#### Scenario: Provider revision crosses the runtime boundary
- **WHEN** the runtime registry records which resolved provider constructed a generation
- **THEN** it SHALL receive only the stable implementation ID and opaque revision fingerprint
- **AND** it SHALL NOT receive package-store paths, mutable archive objects, Android objects, Lua state handles, or repository clients

### Requirement: Provider snapshot publication invokes no provider code under registry synchronization
The registry SHALL construct and validate candidate installed descriptors before its atomic publication boundary. It SHALL NOT parse packages, perform file or network I/O, construct runtimes, invoke configuration migration or validation, create actors, or execute provider callbacks while holding provider-registry synchronization. Snapshot observers SHALL receive one monotonic complete publication and SHALL reconcile outside the provider-registry publication boundary.

#### Scenario: Candidate installed snapshot is prepared
- **WHEN** the package repository materializes descriptors for a candidate snapshot
- **THEN** package parsing, digesting, program-image creation, and descriptor construction SHALL complete before registry publication begins
- **AND** no package callback SHALL execute under registry synchronization

#### Scenario: Snapshot observer reacts to publication
- **WHEN** the registry publishes a new complete installed-provider snapshot
- **THEN** runtime and catalogue reconciliation SHALL run outside provider-registry synchronization
- **AND** unrelated provider resolution SHALL remain available
