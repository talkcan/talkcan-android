# lua-profile-types Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: Packages declare bounded repository-scoped profile types
A package manifest SHALL contain an exact bounded `profileTypes` array. Each declaration SHALL have a canonical package-local `id`, bounded nonblank `label` and optional `help`, schema version exactly `1`, an exact-key data schema, and matching exact-key UI schema. Profile-type identity SHALL be the tuple of the host-resolved declaring repository database ID and local type ID; package name, owner coordinates, release tag, display label, digest, and installation order SHALL NOT establish identity. Type IDs SHALL be unique within one package, and static validation SHALL execute no Lua or secret/network effect.

#### Scenario: Package declares OpenAI-compatible type
- **WHEN** a valid package declares local type `openai_compatible`
- **THEN** the host SHALL derive its durable type identity from the declaring repository ID and local ID
- **AND** package updates from the same repository SHALL address the same type

#### Scenario: Profile declaration is malformed
- **WHEN** a type duplicates an ID, uses unknown keys, invalid schema, unsupported field/control, or exceeds bounds
- **THEN** static validation SHALL reject the complete package before storage or publication

### Requirement: Profile schemas support bounded scalar and protected secret fields
A profile data schema SHALL be an exact-key object with `additionalProperties=false` and a bounded flat field array. Fields MAY have type `string`, `boolean`, `integer`, or `secret`. Scalar declaration, default, allowed-value, range, ID, and UI matching rules SHALL follow the generic channel-configuration contract where applicable. A secret field SHALL have no plaintext default, allowed values, range, or persisted scalar value and SHALL use exactly the protected `secret` UI control. Every field SHALL appear exactly once in UI order. Unknown nested keys and type/control mismatches SHALL reject the package.

#### Scenario: Valid secret field is declared
- **WHEN** a profile schema declares a required `api_key` secret field with a matching protected control
- **THEN** validation SHALL accept the declaration without creating protected storage or a Lua state

#### Scenario: Secret has plaintext default
- **WHEN** a secret declaration contains a default or allowed value
- **THEN** validation SHALL reject the complete package
- **AND** the value SHALL not enter package or profile storage

### Requirement: Users create and manage named global profiles through generic UI
For each published profile type, the host SHALL offer generic create, edit, and delete operations independent of channel instances. A profile SHALL have a stable host-generated nonblank profile ID, host-owned bounded display name, exact declaring type identity, validated scalar values, and protected secret-reference state. Profile mutations SHALL validate the complete candidate and commit metadata and secret changes atomically. Multiple instances of the declaring package SHALL be able to select one profile without duplicating its endpoint or secret.

#### Scenario: User creates profile
- **WHEN** the user saves a valid name, scalar values, and required secrets for a published type
- **THEN** the host SHALL commit one stable profile associated with that exact type identity
- **AND** it SHALL make the profile available to eligible selectors for the declaring package

#### Scenario: Two instances share profile
- **WHEN** two channel instances from the declaring package select the same profile ID
- **THEN** each configuration SHALL persist that same scalar ID
- **AND** editing the global profile SHALL affect subsequent resolution for both without rewriting either channel payload

### Requirement: Profile consumption is restricted to the declaring package
Only code from the repository identity that declared a profile type SHALL enumerate, select, resolve, or read profiles of that type. A package with a coincident local type ID, label, schema, owner name, or source content SHALL receive no authority. Cross-package references, dependencies on another repository's type, and central profile-type repositories SHALL not be implemented in this contract. The durable tuple identity and profile records SHALL avoid assumptions that would prevent a later explicit dependency mechanism.

#### Scenario: Unrelated package requests profile
- **WHEN** another installed package supplies a profile ID owned by the first package's type
- **THEN** editor resolution and runtime lookup SHALL deny it without exposing profile fields or secret presence

#### Scenario: Repository is renamed
- **WHEN** the declaring GitHub repository changes owner or coordinates but retains its database ID
- **THEN** type and profile identities SHALL remain unchanged

### Requirement: Channel configuration selects profiles through stable typed references
A package configuration field MAY declare a dynamic choice whose source is one of its profile types. The editor SHALL list bounded eligible profiles by stable profile ID and display name, persist only the selected profile ID, preserve missing selections without substitution, and include exact type validity in readiness context. Profile selection SHALL NOT copy scalar values or secrets into channel configuration. Selecting a profile grants that runtime generation access only to that profile under package capability policy.

#### Scenario: User selects profile
- **WHEN** a channel editor resolves profiles of its declared required type and selects one
- **THEN** the channel configuration SHALL persist only the stable profile ID
- **AND** the startup configuration snapshot SHALL contain only that ID

#### Scenario: Selected profile is deleted
- **WHEN** a referenced profile is deleted
- **THEN** the channel SHALL preserve its configured ID and become unavailable with a typed missing-profile state
- **AND** the host SHALL not select a sibling profile automatically

### Requirement: `talkcan.profiles` exposes bounded package-local resolution
The host SHALL reserve and inject `talkcan.profiles`. It SHALL expose synchronous `get(profile_id)` returning a detached exact profile value containing stable `id`, `type`, `name`, scalar `values`, and opaque state-local references for secret fields. Lookup SHALL require current package ownership and a profile grant derived from the selected configuration or resolver request. Returned data SHALL contain no protected plaintext, repository client, Android object, SDK client, mutable host profile, or platform credential object. An unavailable, foreign, stale, malformed, or deleted profile SHALL return a normalized error.

#### Scenario: Managed task resolves selected profile
- **WHEN** a live runtime resolves the exact profile selected in its validated configuration
- **THEN** it SHALL receive detached scalar values and opaque secret references for that state
- **AND** mutating the Lua table SHALL not alter the persisted profile

#### Scenario: Runtime resolves unselected profile
- **WHEN** runtime code guesses another valid profile ID of its package
- **THEN** lookup SHALL return `E_DENIED`
- **AND** it SHALL not reveal that profile's scalar values or secret presence

### Requirement: Profile changes reconcile dependent runtimes and resolvers
Creating an unrelated profile SHALL refresh eligible editor choices without replacing running generations. Editing or deleting a profile SHALL atomically invalidate cached choices and secret references, cancel active resolver invocations using the predecessor revision, and reconcile every dependent channel to a fresh generation or typed unavailable state. Predecessor tasks and work effects SHALL follow their existing cancellation/indeterminate rules; no late profile result SHALL update a successor.

#### Scenario: Selected profile endpoint changes
- **WHEN** the user commits a new base URL for a selected profile
- **THEN** dependent runtime generations SHALL be replaced before new work uses the new revision
- **AND** old in-flight external effects SHALL not publish as current work

#### Scenario: Unrelated profile changes
- **WHEN** a profile with no dependent instances is edited
- **THEN** unrelated channel generations SHALL remain live

### Requirement: Package update, rollback, removal, and reinstall preserve profile safety
A package update SHALL validate all retained profiles against the new declaration of each same-identity type. Incompatible profiles SHALL remain stored unchanged and become explicitly unavailable; they SHALL not be coerced or defaulted. Rollback SHALL revalidate them against the restored schema. Package removal SHALL revoke runtime and resolver access but SHALL preserve profile metadata and protected references for explicit reinstall or later user deletion. A different repository SHALL not inherit them. Profile storage corruption SHALL isolate the affected type/profile where possible and fail closed.

#### Scenario: Update changes profile schema incompatibly
- **WHEN** an active package revision rejects an existing profile payload
- **THEN** the host SHALL preserve the exact payload/reference state and mark the profile unavailable
- **AND** dependent channels SHALL preserve their selected profile IDs

#### Scenario: Declaring package is removed
- **WHEN** the user removes the installed package
- **THEN** its profile types SHALL cease publication and all access SHALL be revoked
- **AND** stored profiles SHALL not be reassigned, exposed to other packages, or silently deleted

### Requirement: Profile metadata and UI are bounded and privacy preserving
The host SHALL enforce finite type count, profile count, field count, string bytes, document bytes, secret bytes, and operation deadlines per repository/type and process. Choice declarations SHALL NOT have a separate count limit; their aggregate size remains bounded by the enclosing document and per-value/label byte limits. UI and diagnostics MAY show declared labels, profile display names, scalar fields intended by their controls, secret presence, type/source identity, and normalized outcomes. They SHALL NOT show plaintext secrets, protected aliases, keystore details, or another package's profiles.

#### Scenario: Profile capacity is exhausted
- **WHEN** creating a profile would exceed an applicable bound
- **THEN** the host SHALL reject it before protected or metadata mutation
- **AND** existing profiles SHALL remain unchanged

