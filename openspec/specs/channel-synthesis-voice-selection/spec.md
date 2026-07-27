## Purpose

Define the host-owned synthesis voice selection contract for synthesis-capable channel instances: a per-channel voice selector kept separate from provider configuration, semantic per-instance default resolution, non-disruptive preference updates, and surfacing of availability and assignment dependencies.

## Requirements

### Requirement: Synthesis-capable channels expose host voice selection
The system SHALL expose a host-owned synthesis voice preference for channel instances whose implementation descriptor declares synthesis capability. The selector SHALL remain separate from provider-declared configuration fields and provider payload validation. It SHALL offer the application default, every available compatible built-in profile, and every available eligible custom profile by stable profile identity.

#### Scenario: Existing synthesis-capable channel is configured
- **WHEN** the user opens configuration for a channel whose descriptor declares synthesis capability
- **THEN** the system SHALL show the channel's host synthesis voice selector separately from provider fields
- **AND** selecting a profile SHALL NOT add or change any key in the provider payload

#### Scenario: New synthesis-capable channel is created
- **WHEN** the user creates a channel whose descriptor declares synthesis capability
- **THEN** the creation surface SHALL allow an application-default or available profile selection
- **AND** the resulting channel definition SHALL persist provider configuration and host preference under the same stable instance ID

#### Scenario: Channel does not declare synthesis capability
- **WHEN** the user creates or configures a channel whose descriptor does not declare synthesis capability
- **THEN** the system SHALL NOT show a synthesis voice selector
- **AND** it SHALL retain any persisted host preference without injecting it into provider configuration

#### Scenario: Provider mode does not currently synthesize
- **WHEN** a provider declares synthesis capability but its current opaque configuration mode does not call synthesis
- **THEN** the host MAY continue to show the selector based on declared capability
- **AND** it SHALL NOT infer capability use from provider-owned field values

### Requirement: Semantic default voice resolves per channel instance
For every channel synthesis request carrying `SpeechVoice("default")`, the host SHALL resolve synthesis parameters using the requesting `CapabilityScopeIdentity.channelInstanceId`. An explicit channel profile assignment SHALL resolve to that profile. No assignment SHALL resolve to the verified shipped application default. Profile selection and resolution SHALL remain host policy and SHALL NOT expose internal paths or local profile IDs to Lua.

#### Scenario: Two channels use different profiles
- **WHEN** two live channel instances have different available profile assignments and each requests `voice="default"`
- **THEN** each request SHALL synthesize with the profile assigned to its own instance ID
- **AND** neither request SHALL observe the sibling assignment

#### Scenario: Channel uses application default
- **WHEN** a channel has no explicit profile assignment and requests `voice="default"`
- **THEN** the host SHALL use the verified shipped application-default profile

#### Scenario: Explicit assignment is unavailable
- **WHEN** a channel's assigned custom profile is missing, corrupt, incompatible, or otherwise unavailable
- **THEN** `voice="default"` synthesis for that channel SHALL return typed unavailable/not-configured behavior
- **AND** the host SHALL NOT silently substitute the application default or another profile

#### Scenario: Runtime requests another voice ID
- **WHEN** a channel runtime requests a voice ID other than `default`
- **THEN** the host SHALL retain existing unsupported/not-configured behavior
- **AND** it SHALL NOT interpret the value as a profile ID or filesystem path

### Requirement: Voice preference updates do not replace channel runtimes
A host synthesis voice preference change SHALL affect the next synthesis resolution without modifying the provider configuration payload, replacing the runtime generation, or interrupting an already resolved in-flight synthesis request.

#### Scenario: Preference changes while runtime is live
- **WHEN** the user changes a live channel from one available profile to another
- **THEN** the next `voice="default"` synthesis request SHALL resolve the new profile
- **AND** the runtime generation and provider configuration SHALL remain unchanged

#### Scenario: Preference changes during synthesis
- **WHEN** a synthesis request has already resolved a complete profile and the user changes the channel preference
- **THEN** the in-flight request SHALL complete or cancel under existing lifecycle rules using its resolved profile
- **AND** subsequent requests SHALL resolve the new preference

### Requirement: Channel voice selection surfaces availability and assignment dependencies
The channel selector SHALL identify unavailable persisted selections, compatibility-unverified custom profiles, and the channel dependencies that prevent profile deletion. Untagged imported profiles SHALL require explicit acknowledgement before first channel assignment.

#### Scenario: Persisted profile becomes unavailable
- **WHEN** channel configuration is displayed after its assigned profile becomes unavailable
- **THEN** the selector SHALL preserve and display the unavailable assignment with an actionable diagnostic
- **AND** it SHALL require explicit reassignment rather than silently selecting the application default

#### Scenario: Unverified import is assigned
- **WHEN** the user selects an untagged shape-valid imported profile for a channel for the first time
- **THEN** the system SHALL require explicit acknowledgement of unverified model compatibility
- **AND** cancellation SHALL leave the existing assignment unchanged

#### Scenario: Assigned profile is inspected for deletion
- **WHEN** profile management evaluates deletion of a profile assigned to channels
- **THEN** it SHALL resolve dependent channel display names from stable channel IDs
- **AND** it SHALL require those channels to be reassigned before deletion
