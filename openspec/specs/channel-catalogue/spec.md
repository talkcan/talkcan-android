## Purpose

Define the authoritative persisted catalogue, provider-backed channel instance identity, ordering, selection, availability, and legacy migration contract.

## Requirements

### Requirement: Channel catalogue is the authoritative ordered source
The system SHALL maintain one persisted, ordered catalogue of channel definitions. The catalogue MAY be empty. When the catalogue is nonempty, the system SHALL maintain exactly one active channel instance whose ID exists in the catalogue; an empty catalogue SHALL have no active channel. Each definition SHALL contain a stable opaque instance ID, display name, enabled state, a stable channel implementation provider reference, a provider configuration schema version, a losslessly preserved opaque provider configuration payload, and typed host-owned channel preferences separate from that payload. Instance IDs SHALL NOT change when a channel is renamed, reordered, migrated, temporarily unavailable, or assigned a different host preference and SHALL NOT be derived from display name, provider reference, list position, or voice profile. Persisted legacy definitions referencing a removed built-in provider (`builtin:debug`, `builtin:journal`, `builtin:keyboard`, or `builtin:openai-agent`) SHALL remain as exact, unavailable records with no automatic rebinding to external providers.

#### Scenario: Catalogue loads valid definitions
- **WHEN** the application loads a valid persisted catalogue
- **THEN** it SHALL publish the definitions in persisted list order
- **AND** it SHALL publish the persisted active instance ID
- **AND** each definition SHALL retain its stable provider reference, complete opaque configuration payload, and typed host preferences

#### Scenario: Empty catalogue is valid
- **WHEN** the persisted catalogue contains no definitions
- **THEN** the system SHALL publish an empty catalogue with no active channel
- **AND** it SHALL NOT treat the empty catalogue as an invalid catalogue

#### Scenario: Multiple instances share a provider
- **WHEN** two channel definitions reference the same channel implementation provider
- **THEN** each definition SHALL retain an independent instance ID, configuration payload, and host preferences
- **AND** both SHALL appear independently in the ordered catalogue

#### Scenario: Same-provider configuration update is isolated
- **WHEN** one of multiple same-provider instances receives a configuration or host-preference update addressed by its instance ID
- **THEN** only that definition SHALL change
- **AND** every sibling definition, catalogue order, and active ID SHALL remain unchanged

#### Scenario: Provider is unavailable
- **WHEN** a structurally valid definition references a provider that is missing, incompatible, or failed to load
- **THEN** the catalogue SHALL retain and publish that definition in its persisted position
- **AND** it SHALL preserve the definition's instance ID, provider reference, schema version, configuration payload, and host preferences without loss
- **AND** provider unavailability SHALL NOT invalidate the remaining catalogue

#### Scenario: Invalid catalogue is rejected
- **WHEN** a persisted catalogue has an unsupported catalogue document version, duplicate or blank instance IDs, a blank provider reference, malformed host preferences, or an active ID outside the definition list
- **THEN** the system SHALL NOT publish the invalid catalogue as runtime state
- **AND** it SHALL surface an actionable load failure rather than silently constructing partial defaults

#### Scenario: Persisted legacy built-in definitions are preserved but unavailable
- **WHEN** the catalogue contains a definition referencing a removed built-in provider reference (`builtin:debug`, `builtin:journal`, `builtin:keyboard`, or `builtin:openai-agent`)
- **THEN** the system SHALL preserve that definition's instance ID, display name, enabled state, provider reference, schema version, opaque configuration payload, and host preferences without deletion or mutation
- **AND** the instance SHALL be represented as unavailable through the missing-provider path
- **AND** the system SHALL NOT rebind or map it to an installed external provider identity
- **AND** it SHALL NOT copy or substitute its configuration or select it for active PTT execution

#### Scenario: Installing external packages does not migrate legacy built-in instances
- **WHEN** an external package implementing equivalent user-visible behavior is installed and registered with its repository-derived provider reference
- **THEN** existing catalogue definitions referencing a removed built-in provider SHALL remain unavailable
- **AND** the host SHALL NOT automatically migrate, update, copy configuration from, or associate those legacy definitions with the newly installed provider
- **AND** the user SHALL be required to manually create a new instance of the installed provider

#### Scenario: Existing catalogue migrates to host preferences
- **WHEN** the application loads a supported catalogue document written before typed host preferences existed
- **THEN** it SHALL preserve every definition, order, active ID, provider reference, schema version, and opaque provider payload
- **AND** it SHALL initialize empty/default host preferences for every definition
- **AND** it SHALL rewrite only the upgraded catalogue document after retaining existing legacy backup guarantees

### Requirement: Host preferences remain isolated from provider configuration
The catalogue SHALL persist host-owned channel preferences as a typed object distinct from the provider-owned configuration schema version and opaque payload. Provider validation, migration, runtime construction, and Lua configuration snapshots SHALL receive only the provider payload. A host-preference-only update SHALL NOT trigger provider migration or runtime generation replacement.

#### Scenario: Host voice preference is saved
- **WHEN** the user assigns a synthesis voice profile to a channel instance
- **THEN** the catalogue SHALL persist the stable profile ID in that definition's typed host preferences
- **AND** the provider configuration payload and schema version SHALL remain byte-semantically unchanged

#### Scenario: Provider configuration is updated
- **WHEN** the user submits valid provider configuration without changing host preferences
- **THEN** the catalogue SHALL retain the definition's complete host preferences
- **AND** provider validation SHALL not receive the host preference object

#### Scenario: Channel with host preferences is removed
- **WHEN** the user removes a channel definition
- **THEN** its host preferences SHALL be removed atomically with that definition
- **AND** sibling definitions and their preferences SHALL remain unchanged

#### Scenario: Channel creation includes host preference
- **WHEN** a channel is created with valid provider configuration and a valid host synthesis voice preference
- **THEN** the catalogue SHALL commit both under the same newly allocated stable instance ID
- **AND** it SHALL not expose the host preference as an undeclared provider field

### Requirement: Provider-backed instances can be added and updated
The system SHALL allow the user to create an instance from any available registered channel implementation provider, assign a display name, and edit that instance's configuration through the provider's schema. New instances SHALL receive unique opaque IDs, SHALL store the provider's stable reference, and SHALL store the provider configuration as a versioned opaque payload without converting it to a host-owned built-in configuration algebra.

#### Scenario: Add an instance from a registered provider
- **WHEN** the user creates a channel using an available registered provider and configuration valid for that provider's current schema
- **THEN** the system SHALL append a new definition to the catalogue
- **AND** the new definition SHALL have an ID distinct from every existing definition
- **AND** it SHALL persist the provider reference, current schema version, and complete validated payload

#### Scenario: Removed seeded provider instance can be recreated
- **WHEN** a migrated seed instance of an available built-in provider is removed
- **THEN** the user SHALL remain able to create another instance from that provider with a new opaque ID
- **AND** existing instances from that provider SHALL NOT prevent additional instances

#### Scenario: Rename a channel
- **WHEN** the user changes a channel instance's display name
- **THEN** the system SHALL persist the new display name
- **AND** the instance ID, provider reference, configuration payload, active state, and list position SHALL remain unchanged

#### Scenario: Reject invalid configuration
- **WHEN** an add or update request contains configuration invalid for the referenced provider and declared schema version
- **THEN** the system SHALL reject the mutation
- **AND** the persisted catalogue SHALL remain unchanged

#### Scenario: Missing provider cannot validate an edit
- **WHEN** a configuration edit is requested for an instance whose provider is unavailable
- **THEN** the system SHALL reject the configuration mutation with an actionable unavailable result
- **AND** it SHALL preserve the stored provider reference, schema version, and complete payload without field loss or reinterpretation

### Requirement: Legacy channel settings migrate once
When no catalogue document exists, the system SHALL construct and atomically persist an initial empty provider-backed catalogue containing no channel definitions and no active channel. The system SHALL NOT seed any built-in channel instance (Journal, Keyboard, OpenAI Agent, or Debug). Legacy channel preferences (save-voice, save-text, active-channel) SHALL NOT produce channel definitions and SHALL be discarded. After a catalogue is committed, it SHALL be the only channel-definition source of truth.

#### Scenario: First start with legacy settings
- **WHEN** no catalogue exists and legacy channel preferences exist
- **THEN** the system SHALL create and persist an empty catalogue with no definitions and no active channel
- **AND** it SHALL NOT create any built-in channel definition
- **AND** it SHALL persist the complete catalogue before publishing it

#### Scenario: Migration commit fails
- **WHEN** creating the initial empty catalogue fails to commit
- **THEN** the system SHALL retain the legacy preferences
- **AND** it SHALL retry migration on a subsequent start rather than marking migration complete
- **AND** it SHALL NOT publish a partially created catalogue

#### Scenario: Catalogue already exists
- **WHEN** a valid provider-backed catalogue document exists
- **THEN** the system SHALL load that catalogue
- **AND** it SHALL NOT merge or overwrite it from legacy preference keys

### Requirement: Provider configuration migration is lossless and atomic
When an available provider declares a configuration schema version newer than a stored instance payload, the system SHALL ask that provider to migrate the opaque payload forward in declared version steps and validate the result. The system SHALL preserve every payload field not intentionally transformed by the provider. It SHALL atomically commit the complete next catalogue before publishing definitions or constructing runtimes from migrated payloads; a migration or commit failure SHALL leave the previously persisted catalogue unchanged.

#### Scenario: Configuration payload migrates forward
- **WHEN** an instance stores an older supported configuration schema version and its provider successfully migrates and validates the payload
- **THEN** the system SHALL atomically persist the complete catalogue with that instance's migrated version and payload
- **AND** it SHALL preserve the instance ID, provider reference, display name, enabled state, catalogue position, active selection, and all untransformed payload fields
- **AND** it SHALL publish or instantiate the migrated definition only after the commit succeeds

#### Scenario: One payload migration fails
- **WHEN** any required provider migration step fails, returns an invalid payload, or cannot reach the provider's current schema version
- **THEN** the system SHALL NOT commit or publish a partially migrated catalogue
- **AND** every previously persisted definition, ordering position, active ID, schema version, and payload SHALL remain unchanged
- **AND** the affected instance SHALL be represented as unavailable with an actionable migration failure

#### Scenario: Provider is absent during forward migration
- **WHEN** an instance references an unavailable provider and its stored payload cannot be assessed or migrated
- **THEN** the system SHALL preserve that instance and its opaque payload without modification
- **AND** it SHALL represent the instance as unavailable rather than deleting it or synthesizing defaults
