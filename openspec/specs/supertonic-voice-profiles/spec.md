## Purpose

Define the Supertonic-3 voice profile system: a unified catalogue of built-in and custom profiles, weighted mixing of compatible profiles, bounded reversible latent editing, atomic stack-compatible persistence, SAF import/export, host-owned preview routing, and an assignment-preserving profile lifecycle.

## Requirements

### Requirement: Host publishes one compatible voice-profile catalogue
The system SHALL publish one bounded catalogue of application-provided built-in Supertonic-3 profiles and user-created custom profiles. Built-in profiles SHALL be read-only references to verified application model assets. Custom profiles SHALL include profiles created by the editor, previously mixed profiles, and compatible imported profiles. Every profile SHALL have a stable identity independent of display name and filename, a bounded display name, a profile kind, an availability state, and model-compatibility state.

#### Scenario: Catalogue contains built-in and custom profiles
- **WHEN** verified built-in styles and valid custom profiles are available
- **THEN** the profile catalogue SHALL expose both groups through one selectable source list
- **AND** built-in profiles SHALL be marked read-only
- **AND** custom profiles SHALL be eligible for subsequent mixes

#### Scenario: Previously mixed profile is selected as a source
- **WHEN** the user saves a valid mixed profile and starts another mix
- **THEN** the saved profile SHALL appear as a normal custom source
- **AND** the new mix SHALL read its materialized tensors without requiring its parent profiles

#### Scenario: Profile is unavailable or incompatible
- **WHEN** a custom profile file is missing, corrupt, non-finite, shape-invalid, or declares an incompatible model family
- **THEN** the catalogue SHALL retain a diagnostic profile entry when metadata exists
- **AND** the profile SHALL NOT be selectable for mixing, preview, or channel assignment

### Requirement: Mixer combines any two or more compatible profiles
The system SHALL allow the user to select between 2 and 16 distinct available compatible profiles from the unified catalogue and SHALL materialize a new draft by weighted mixing of both `style_ttl` and `style_dp`. Sources MAY be any combination of built-in, imported, editor-created, or previously mixed custom profiles. Effective weights SHALL be finite, strictly positive, normalized to sum to one, and available through equal, manual, and randomized controls.

#### Scenario: Built-in and custom profiles are mixed
- **WHEN** the user selects a built-in profile and one or more custom profiles with valid weights
- **THEN** the mixer SHALL normalize the weights
- **AND** every TTL output element SHALL equal the weighted sum of the corresponding source TTL elements within floating-point tolerance
- **AND** every DP output element SHALL equal the weighted sum of the corresponding source DP elements within floating-point tolerance

#### Scenario: Multiple custom profiles are mixed
- **WHEN** the user selects two or more compatible custom profiles
- **THEN** the mixer SHALL accept them under the same rules as built-in sources
- **AND** the resulting draft SHALL contain complete materialized tensors independent of the selected sources after creation

#### Scenario: Equal weights are requested
- **WHEN** the user selects at least two profiles and requests equal weights
- **THEN** every selected source SHALL receive effective weight `1 / sourceCount`
- **AND** the UI SHALL display the normalized percentages

#### Scenario: Random weights are requested
- **WHEN** the user requests randomized weights for at least two selected profiles
- **THEN** the mixer SHALL generate positive weights and normalize them to sum to one
- **AND** the resulting exact normalized weights SHALL be retained in draft provenance

#### Scenario: Mix selection is invalid
- **WHEN** fewer than two distinct profiles have positive effective weight, more than 16 sources are selected, a source is unavailable, or any weight is non-finite
- **THEN** the system SHALL reject mix creation without changing the current draft

#### Scenario: Mixed tensor is invalid
- **WHEN** mixing would produce a non-finite value or a shape other than TTL `[1,50,256]` and DP `[1,8,16]`
- **THEN** the system SHALL reject the complete mix
- **AND** it SHALL NOT publish or persist a partial draft

### Requirement: Editor provides bounded reversible latent operations
After materializing a mix or copying one available profile into a draft, the system SHALL provide heatmap visualization, reset, bounded undo, and attributed Kotlin implementations of the source editor's TTL operations: feature mirror, time mirror, sign inversion, scalar addition, scalar multiplication, time derivative, seeded random feature/time roll, feature sharpen, quantize, feature echo, feature tremolo, and seeded jitter. These operations SHALL modify TTL only and SHALL retain the draft's materialized DP tensor.

#### Scenario: User applies a deterministic operation
- **WHEN** the user applies a deterministic operation with valid parameters
- **THEN** the editor SHALL compute the complete candidate TTL tensor off the UI thread when appropriate
- **AND** it SHALL commit the candidate only when shape and finiteness validation succeeds
- **AND** it SHALL retain the previous draft in bounded undo history

#### Scenario: User applies a random operation
- **WHEN** the user applies random shift or jitter
- **THEN** the editor SHALL use an explicit seed
- **AND** the operation kind, parameters, and seed SHALL be recorded in draft provenance
- **AND** repeating the operation from the same input with the same seed SHALL produce the same result

#### Scenario: User resets edited mix
- **WHEN** the user requests reset after applying latent operations
- **THEN** TTL SHALL return to the materialized weighted-mix baseline
- **AND** DP and the source weights SHALL remain unchanged

#### Scenario: User changes sources after editing
- **WHEN** a draft has latent operations and the user changes mix sources or weights
- **THEN** the system SHALL require creation or reset of the mixed baseline
- **AND** it SHALL NOT silently replay prior operations against the new sources

#### Scenario: Operation is invalid
- **WHEN** an operation parameter is invalid or any candidate element is non-finite
- **THEN** the operation SHALL fail without mutating the draft or undo history

### Requirement: Custom profiles persist atomically in stack-compatible format
The system SHALL store custom profiles outside the verified Supertonic model directory under stable profile IDs. Custom profile documents SHALL use nested JSON data matching TTL `[1,50,256]` and DP `[1,8,16]`, SHALL remain readable by the existing `SupertonicVoiceStyle` parser, and SHALL be committed atomically with versioned metadata. The store SHALL enforce at most 64 custom profiles, 128 UTF-8 bytes per unique case-insensitive display name, 2 MiB per imported document before parsing, and 32 MiB aggregate custom-profile storage.

#### Scenario: Mixed profile is saved
- **WHEN** a valid draft is saved with an available unique display name and storage bounds permit it
- **THEN** the system SHALL allocate a new stable profile ID
- **AND** it SHALL atomically persist complete nested TTL and DP data plus immediate-source provenance
- **AND** it SHALL publish the saved profile as an available custom source

#### Scenario: Custom source is mixed and saved
- **WHEN** a custom profile participates in a new mix
- **THEN** saving the result SHALL allocate a new profile ID rather than overwrite a selected source
- **AND** its provenance SHALL reference only immediate source IDs and resolved weights

#### Scenario: Persistence is interrupted
- **WHEN** writing a profile or metadata index fails before atomic commit
- **THEN** the previous valid catalogue and profile files SHALL remain authoritative
- **AND** no partial profile SHALL become available

#### Scenario: Storage limit is exceeded
- **WHEN** saving or importing would exceed a profile count, document size, name, or aggregate byte bound
- **THEN** the complete operation SHALL fail with an actionable bounded-storage reason
- **AND** existing profiles SHALL remain unchanged

### Requirement: Users can import and export compatible profile JSON through SAF
The system SHALL import profile documents through Android SAF open-document access and export profiles through SAF create-document access without broad filesystem permission. Import SHALL enforce byte bounds before parsing, the existing nested style contract, exact shapes, finite values, and declared model compatibility. Export SHALL emit nested stack-compatible JSON regardless of internal formatting.

#### Scenario: Compatible profile is imported
- **WHEN** the user selects a bounded nested Supertonic-3 profile document with valid tensors
- **THEN** the system SHALL persist it under a new stable custom profile ID
- **AND** it SHALL become available for mixing after commit

#### Scenario: Untagged compatible profile is imported
- **WHEN** an imported document has valid exact shapes and finite values but no model-version metadata
- **THEN** the system SHALL mark it compatibility-unverified
- **AND** it SHALL require explicit user acknowledgement before channel assignment

#### Scenario: Flat or malformed profile is imported
- **WHEN** an imported document uses flat tensor data, malformed nesting, excessive bytes, wrong dimensions, non-finite values, or a declared incompatible model family
- **THEN** the system SHALL reject it without retaining a partial profile

#### Scenario: Profile is exported
- **WHEN** the user chooses a destination for an available profile
- **THEN** the system SHALL write complete nested TTL and DP data accepted by the existing stack
- **AND** it SHALL not expose internal profile-store paths

### Requirement: Voice preview reuses host-owned synthesis and routing
The editor SHALL preview the current valid draft through the existing service-owned Supertonic synthesizer and host audio coordinator. It SHALL serialize a changed draft to one atomic cache document on explicit preview, route playback through the active input mode, display preview status and target, and treat preview as lower priority than operational PTT/channel audio.

#### Scenario: User previews a valid mix
- **WHEN** the model and active-mode output route are available and no operational audio owns the route
- **THEN** the system SHALL synthesize the preview text using the current complete draft
- **AND** it SHALL play the result through the active input mode's host route
- **AND** it SHALL show synthesizing and playback status

#### Scenario: Draft is unchanged between previews
- **WHEN** the user previews an unchanged valid draft again
- **THEN** the system MAY reuse its existing complete cache document
- **AND** it SHALL NOT persist the preview as a custom profile implicitly

#### Scenario: Operational PTT begins during preview
- **WHEN** capture, channel synthesis, or operational playback begins while preview is pending or active
- **THEN** the system SHALL cancel preview work and release its audio route
- **AND** operational work SHALL retain priority

#### Scenario: User leaves the editor
- **WHEN** the editor surface is disposed with preview pending or active
- **THEN** the system SHALL cancel preview and release editor-owned temporary/audio resources

### Requirement: Custom profile lifecycle preserves assignments and descendants
Profile rename SHALL preserve stable identity. A custom profile assigned to any channel SHALL NOT be deleted until those assignments are changed. Historical provenance references from materialized descendants SHALL NOT block deletion because descendants contain complete tensors.

#### Scenario: Assigned profile deletion is requested
- **WHEN** the user requests deletion of a profile assigned to one or more channels
- **THEN** the system SHALL refuse deletion
- **AND** it SHALL identify the dependent channels requiring reassignment

#### Scenario: Unassigned source profile is deleted
- **WHEN** a custom profile is unassigned but appears in historical provenance of materialized descendants
- **THEN** the system SHALL allow deletion after confirmation
- **AND** every descendant SHALL remain available and retain its historical provenance reference

#### Scenario: Profile is renamed
- **WHEN** the user renames a custom profile to another valid unique display name
- **THEN** its stable profile ID, tensors, channel assignments, and descendant validity SHALL remain unchanged
