## Purpose

Defines the host-managed installed package store, atomic transactions, recovery,
and asynchronously published snapshots for installed channel providers.
## Requirements
### Requirement: Installed packages use a separate provider-level store
The host SHALL maintain installed Lua package revisions in app-private storage separate from the channel catalogue. The installed-package index SHALL map one durable provider identity to exactly one active revision and zero or one rollback revision. A revision SHALL reference immutable exact artifact content by SHA-256 and retain its validated manifest, runtime requirements, presentation, and host-resolved repository/release/asset source record. The host SHALL NOT persist Lua source, artifact path, digest, release version, or package revision in a `ChannelDefinition` or its opaque configuration. Multiple channel instances referencing one installed provider SHALL resolve the same active revision while retaining independent instance IDs and configurations.

#### Scenario: Multiple instances use one installed provider
- **WHEN** two catalogue definitions reference the same installed provider implementation ID
- **THEN** both definitions SHALL resolve the provider's one active package revision
- **AND** neither definition SHALL contain or independently select an artifact digest or package version

#### Scenario: Installed provider has no channel instances
- **WHEN** a valid package is active but no catalogue definition references its provider ID
- **THEN** the package SHALL remain installed and discoverable to host provider surfaces
- **AND** loading the installed index or publishing its provider SHALL NOT create a Lua actor or Lua state

#### Scenario: Package store is absent on upgrade
- **WHEN** the application starts without an installed-package index or content directory
- **THEN** the host SHALL treat installed packages as an empty snapshot
- **AND** it SHALL NOT migrate or rewrite the existing channel catalogue

### Requirement: Package installation is an atomic prepare-commit-publish transaction
An install request SHALL provide one exact artifact and one host-resolved repository/release/asset identity. The installed-package repository SHALL serialize mutations for the addressed provider, stream the artifact into private staging while hashing and bounding it, validate the complete package without executing Lua, commit immutable digest-addressed content, atomically replace the complete installed index, and only then publish a new immutable installed-provider snapshot. Any failure before index commit SHALL preserve the prior committed index, active provider revision, published provider snapshot, and live runtime generations unchanged. Incomplete staging content SHALL never be materialized or published.

#### Scenario: First installation succeeds
- **WHEN** a compatible package passes identity, archive, manifest, source, digest, and program-image validation and storage commit succeeds
- **THEN** its revision SHALL become the active revision for that provider in one committed index transition
- **AND** the host SHALL publish the provider only after that commit

#### Scenario: Validation fails before commit
- **WHEN** an incoming artifact is malformed, incompatible, over bounds, identity-mismatched, or otherwise invalid
- **THEN** the install SHALL return one typed failure
- **AND** the previous index, active package, provider snapshot, catalogue, and runtimes SHALL remain unchanged

#### Scenario: Process stops with incomplete staging content
- **WHEN** the process stops after staging begins but before the complete index commit
- **THEN** restart recovery SHALL retain the previously committed active revision
- **AND** it SHALL discard or ignore the incomplete staging content
- **AND** it SHALL NOT infer a successful install from the presence of staged bytes

### Requirement: Update and idempotent reinstall preserve exact revision semantics
Installing a different validated revision for an existing provider SHALL atomically make the incoming revision active and retain the former active revision as the sole rollback revision. Any older rollback revision SHALL cease to be referenced by the new index and MAY be removed as orphaned content after commit. Installing the same provider, exact source identity, and exact artifact digest as the current active revision SHALL be idempotent and SHALL NOT publish a new provider revision or replace live runtimes. Package-version text alone SHALL NOT determine equality or ordering.

#### Scenario: Provider update succeeds
- **WHEN** a different compatible artifact digest for an installed provider commits successfully
- **THEN** the incoming revision SHALL become active
- **AND** the former active revision SHALL become the only rollback revision
- **AND** the published provider fingerprint SHALL change to the incoming digest

#### Scenario: Exact active revision is reinstalled
- **WHEN** an install request identifies the same provider, exact release/asset source, and exact digest as the active revision
- **THEN** the repository SHALL return the existing active result idempotently
- **AND** it SHALL NOT change the index generation, rollback slot, provider fingerprint, or runtime generation

#### Scenario: Update storage fails
- **WHEN** incoming content validates but content or index commit fails
- **THEN** the former active and rollback bindings SHALL remain authoritative
- **AND** the host SHALL NOT publish the incoming provider or partially advance the index

### Requirement: Rollback is explicit and removal preserves channel definitions
Rollback SHALL be available only when one committed rollback revision exists. The repository SHALL revalidate that exact retained artifact and atomically swap the active and rollback revisions; it SHALL NOT search for another version, download content, or infer ordering from version strings. Removal SHALL atomically delete the provider binding from the installed index and make its active and rollback content eligible for orphan cleanup. Rollback and removal SHALL NOT modify, delete, reorder, disable, rename, or reinterpret channel definitions. The host SHALL NOT automatically roll back after corruption, compatibility failure, runtime failure, or application upgrade.

#### Scenario: Explicit rollback succeeds
- **WHEN** a provider has one valid retained rollback revision and rollback is requested
- **THEN** that exact revision SHALL become active atomically
- **AND** the former active revision SHALL become the rollback revision
- **AND** the provider fingerprint SHALL change to the newly active digest

#### Scenario: No rollback revision exists
- **WHEN** rollback is requested for a provider without a retained rollback revision
- **THEN** the repository SHALL return a typed no-rollback result
- **AND** the active revision and provider snapshot SHALL remain unchanged

#### Scenario: Installed provider is removed
- **WHEN** removal commits for a provider referenced by existing channel definitions
- **THEN** the installed provider SHALL disappear from provider resolution
- **AND** every definition SHALL remain persisted with the same instance ID, provider reference, name, enabled state, order, schema version, and opaque payload
- **AND** those instances SHALL project the ordinary missing-provider state

#### Scenario: Active revision is corrupt but rollback is valid
- **WHEN** active content fails integrity validation and a rollback revision remains valid
- **THEN** the host SHALL mark the provider unavailable with a typed corruption reason
- **AND** it SHALL NOT execute or automatically activate the rollback revision

### Requirement: Installed index and immutable content recover without partial publication
The installed-package index SHALL be replaced atomically as one complete document and SHALL have a last-known committed recovery copy or equivalent atomic-file guarantee. On startup the host SHALL validate the selected complete index before publishing any installed-provider snapshot. A valid recovery copy MAY replace an unreadable current index only as a complete document; the host SHALL NOT merge records or fields from two index generations. Exact content not referenced by the selected committed index SHALL be treated as orphaned and MAY be deleted by bounded cleanup. One corrupt active artifact SHALL make only its provider unavailable while valid sibling providers remain materializable. A globally unrecoverable index SHALL publish no installed providers and one actionable store failure while leaving built-in providers operational.

#### Scenario: Current index is corrupt and recovery copy is valid
- **WHEN** startup cannot decode or validate the current index but has one complete valid committed recovery copy
- **THEN** the host SHALL select the recovery copy as one whole index generation
- **AND** it SHALL NOT merge newer fragments from the corrupt document

#### Scenario: One active artifact is corrupt
- **WHEN** one provider record references missing or digest-mismatched content while sibling records are valid
- **THEN** the affected provider SHALL be published as unavailable with a typed package error
- **AND** valid sibling installed providers and all built-ins SHALL remain operational

#### Scenario: Unreferenced content is recovered
- **WHEN** startup finds exact artifact content that is not referenced by the selected active or rollback index records
- **THEN** the host MAY delete it as orphaned content
- **AND** it SHALL NOT activate, register, or execute it merely because it exists in the content directory

### Requirement: Installed providers publish asynchronously without blocking service startup
The installed-package repository SHALL load, hash, parse, and materialize active package revisions on a bounded host worker or I/O boundary rather than the Android main thread. It SHALL expose host-domain loading, ready-snapshot, and failure states. Built-in provider registration, foreground-service startup, and channel-catalogue preservation SHALL continue while installed packages load. Definitions referencing installed providers SHALL remain preserved and unavailable or loading until a complete installed snapshot is published. Every committed install, update, rollback, removal, or recovery change SHALL publish at most one complete immutable installed-provider snapshot.

#### Scenario: Service starts with installed packages
- **WHEN** application service creation begins with one or more committed installed revisions
- **THEN** built-in providers and the foreground service SHALL initialize without waiting for archive parsing on the Android main thread
- **AND** installed providers SHALL become available only after bounded off-main materialization publishes the complete snapshot

#### Scenario: Snapshot publication races package mutation
- **WHEN** an older load result completes after a newer committed index generation exists
- **THEN** the repository SHALL reject the stale publication
- **AND** it SHALL NOT replace the provider snapshot derived from the newer committed generation

#### Scenario: Installed store is empty
- **WHEN** loading completes with no active installed packages
- **THEN** the repository SHALL publish an empty installed snapshot
- **AND** ordinary Kotlin-provider startup SHALL create no Lua actor or Lua state

### Requirement: Package repository shutdown is bounded and late publications are suppressed
Repository shutdown SHALL stop mutation admission, cancel or join staging and load work within the host shutdown bound, close file resources, discard incomplete staging content, and suppress every later provider-snapshot publication. Committed exact content and index records SHALL survive service/process shutdown. Lua actors, states, timers, coroutines, logs, and generation authorization SHALL remain owned and closed by the existing runtime registry rather than by the package repository.

#### Scenario: Shutdown occurs during installation
- **WHEN** service shutdown begins while an artifact is staged but before index commit
- **THEN** the repository SHALL stop or finish only according to the bounded transaction rule
- **AND** it SHALL not publish an uncommitted provider snapshot after shutdown
- **AND** the previously committed index SHALL remain authoritative

#### Scenario: Shutdown occurs after commit before publication
- **WHEN** the index commit succeeds but shutdown prevents in-process provider publication
- **THEN** the committed index SHALL remain authoritative on the next start
- **AND** restart SHALL reconstruct the installed snapshot from that index
- **AND** no prior-process Lua state or runtime generation SHALL be restored

### Requirement: Revised v1 clean cutover uses exact immutable package revisions
After the host adopts the revised unreleased manifest v1, every production/external development package used by acceptance SHALL be republished as an immutable release asset containing explicit `resources`, including an empty mount list where applicable. Historical pre-cutover assets SHALL remain historical and incompatible; the host SHALL not rewrite their bytes, infer a declaration, or activate them through a legacy parser. Installation/update SHALL continue validating exact downloaded asset bytes before atomic store publication.

#### Scenario: Historical package lacks resources
- **WHEN** an installed or downloaded historical development asset uses the superseded manifest shape
- **THEN** validation SHALL reject it as incompatible with revised v1
- **AND** the store SHALL retain any previously active valid revised revision unchanged

#### Scenario: Revised package is published
- **WHEN** an update asset declares explicit compatible resources and passes exact validation
- **THEN** the transaction SHALL atomically publish it under the same repository-derived provider identity
- **AND** runtime reconciliation SHALL replace affected generations without changing instance identity

### Requirement: Official package acceptance pins exact revised releases
End-to-end dependency acceptance for this change SHALL resolve and install exact immutable assets for:
- Debug: `talkcan-channels/debug`, owner database ID `1224006`, `v1.2.0`, asset `talkcan-channel.zip`.
- Diagnostics: `talkcan-channels/diagnostics`, owner database ID `1224006`, `v1.3.0`, asset `talkcan-channel.zip`.
- Journal: `talkcan-channels/journal`, owner database ID `1224006`, `v1.0.0`, asset `talkcan-channel.zip`.

Each release SHALL be stable/non-draft/non-prerelease, contain exactly one canonical asset of that name, and contain a positive immutable repository database ID matching manifest `repositoryId`. Release immutability in this contract is the project policy that published tags and assets are never replaced; GitHub's optional immutable-releases repository feature is not required. Fixtures, hashes, release metadata, and installation tests SHALL be updated together only from inspected exact assets. Runtime tests SHALL not make live network calls.

#### Scenario: Revised official packages are resolved
- **WHEN** acceptance inspects the three pinned releases
- **THEN** each manifest identity, release version, asset name, digest, and source-only archive SHALL match recorded fixtures
- **AND** each package SHALL install through the normal validator/store transaction

#### Scenario: Exact release is missing or mutable metadata differs
- **WHEN** a pinned release/asset is absent, duplicated, draft/prerelease, owned by another account, or differs from recorded identity/digest
- **THEN** acceptance SHALL fail rather than substituting latest release or another asset

### Requirement: Package updates preserve compatible mount bindings by declaration identity
When an installed provider changes revision, the store/reconciliation path SHALL preserve a channel instance's mount binding only if the new declaration has the same mount ID, kind, and requested access and remains authorized. Removed or incompatible declarations SHALL not leak or silently retarget grants. Failed package update or rollback SHALL leave the prior active revision and bindings intact; successful replacement SHALL create fresh generation-owned mount handles.

#### Scenario: Debug update retains no mounts
- **WHEN** Debug updates between revised revisions that both declare an explicit empty mount list
- **THEN** no mount binding or picker state SHALL be created

#### Scenario: Journal update keeps compatible output declaration
- **WHEN** Journal updates to a revision declaring the same required read-write directory-tree `output` mount
- **THEN** the instance MAY retain its generic persisted binding after revalidation
- **AND** successor Lua SHALL receive a newly created opaque handle, not predecessor userdata

#### Scenario: Journal update changes mount kind or access
- **WHEN** a revision changes the declaration incompatibly
- **THEN** the host SHALL preserve the opaque platform grant for explicit repair/reselection policy but SHALL not bind it automatically
- **AND** the instance SHALL become typed-unavailable until resolved

### Requirement: Installed revisions preserve dynamic choices and keyboard-output eligibility
The installed-package store and index SHALL preserve validated dynamic-choice source declarations and public `keyboard.output` eligibility as part of each immutable package revision. Every load, recovery, materialization, update, rollback, and restart SHALL rehash and reparse exact artifact bytes before trusting those declarations. Cached metadata SHALL NOT synthesize, omit, mutate, or grant a dynamic source or capability, and provider publication SHALL execute no Lua or host effect.

#### Scenario: Keyboard-output package survives restart
- **WHEN** an installed revision declaring `keyboard.output` and dependent keyboard platform, layout, and profile sources is restored after process restart
- **THEN** exact stored bytes SHALL be revalidated into the same declarations before provider publication
- **AND** registration SHALL create no actor, Lua state, dynamic-source lookup, preparation, queue entry, connection, or output operation

#### Scenario: Cached declaration disagrees with artifact
- **WHEN** cached index metadata for a dynamic source or capability differs from the reparsed exact artifact
- **THEN** the host SHALL treat the revision as invalid or corrupted according to the store contract
- **AND** it SHALL not publish authority from the cached metadata

### Requirement: Package replacement revokes predecessor keyboard output atomically
Updating, rolling back, removing, or reinstalling an installed provider SHALL use the existing atomic provider/runtime reconciliation path. Before a successor generation becomes ready, the host SHALL stop predecessor admission, finish or cancel committed callbacks within their contract, revoke predecessor keyboard-output leases and queued operations, close predecessor actor state, and suppress late completions. Compatible scalar profile configuration SHALL remain preserved; incompatible payloads SHALL remain unchanged and explicitly unavailable.

#### Scenario: Package update replaces live generation
- **WHEN** a live keyboard-output package revision is explicitly updated
- **THEN** predecessor keyboard-output authority and queued not-yet-effective operations SHALL be revoked before successor readiness
- **AND** the successor SHALL acquire distinct generation authority using the preserved compatible configuration

#### Scenario: Package removal preserves catalogue instance
- **WHEN** the external Keyboard package is removed while catalogue instances reference it
- **THEN** those definitions and scalar payloads SHALL remain preserved through the generic missing-provider path
- **AND** no built-in Keyboard provider SHALL be substituted or rebound

### Requirement: Installed keyboard-output packages receive no identity special case
Official classification, provider identity, installation, materialization, instance creation, configuration, update, rollback, removal, and reinstall SHALL depend only on generic repository/release/asset identity and validated package declarations. The application SHALL NOT branch on the external Keyboard repository coordinates, repository ID, label, manifest version string, asset digest, or implementation ID to grant capabilities or create behavior.

#### Scenario: Community package declares keyboard output
- **WHEN** a package from another repository passes the same validation and declares `keyboard.output`
- **THEN** it SHALL receive the same generic provider and runtime contracts
- **AND** official owner classification SHALL not change its API shape or host authorization semantics

### Requirement: Installed snapshots publish profile types and resolver metadata atomically
Each committed active package revision SHALL publish its validated profile-type and choice-resolver declarations as part of the same immutable installed-provider snapshot used for provider materialization. Installation/update SHALL not expose a type or resolver before the complete artifact, index, source map, declarations, and provider revision commit. Rollback SHALL atomically restore predecessor declarations. Removal/corruption SHALL unpublish affected declarations and cancel active resolver invocations without affecting valid siblings.

#### Scenario: Package installation commits
- **WHEN** a package with profile types and resolvers passes complete validation and store commit
- **THEN** provider, profile types, resolver factories, and configuration sources SHALL become visible from one snapshot generation
- **AND** no partial metadata SHALL be observable

#### Scenario: Candidate update fails
- **WHEN** a new revision has an invalid resolver module or profile schema
- **THEN** the prior active revision and declarations SHALL remain authoritative
- **AND** no candidate actor or profile migration SHALL execute

### Requirement: Package lifecycle preserves repository-owned profiles safely
Profile records and protected secret references SHALL be stored independently from active package artifacts while retaining exact declaring repository/type identity. Update and rollback SHALL revalidate them without coercion. Package removal or active-artifact corruption SHALL preserve records but revoke publication/access and mark dependents unavailable. Reinstall of the same durable repository/type MAY restore access after validation; a different repository or coordinate reuse SHALL not inherit profiles.

#### Scenario: Package is removed
- **WHEN** the user removes a provider with existing profiles
- **THEN** its type and profiles SHALL become unavailable to editors/runtimes
- **AND** metadata and protected references SHALL remain for explicit reinstall or deletion

#### Scenario: Same repository is reinstalled
- **WHEN** a valid later artifact declares the same repository-scoped type
- **THEN** retained profiles SHALL be revalidated against that schema before publication
- **AND** incompatible profiles SHALL remain preserved and unavailable rather than defaulted

### Requirement: Package lifecycle reconciles durable work by exact revision cause
Durable queue records SHALL remain in the generic work store rather than package content/index documents. Ordinary process restart with the same active artifact/configuration/profile revision SHALL permit safe reclamation. Update, rollback, removal, provider corruption, configuration/profile replacement, or instance deletion SHALL retire affected work epochs: unstarted nonterminal work becomes cancelled, started uncommitted effects indeterminate, and terminal tombstones remain bounded. Activating another repository SHALL never claim predecessor work.

#### Scenario: Package update occurs with queued work
- **WHEN** an explicit update activates a new package revision
- **THEN** predecessor safe queued items SHALL be cancelled rather than executed by changed source
- **AND** the successor SHALL start a fresh work epoch

#### Scenario: Startup reloads unchanged package
- **WHEN** exact active revision and dependencies reload after process death
- **THEN** materialization SHALL reconnect safe work to the fresh runtime without storing Lua state in the package index

### Requirement: New declarations remain statically inspectable before trust and activation
Package candidate inspection and trust UI SHALL present bounded declared profile types, resolver presence and requested capabilities, work queue IDs, and security-relevant combinations including `secrets.read` plus unrestricted `network.http` before activation. Inspection SHALL use only validated manifest/source metadata and SHALL not execute a resolver, read a profile/secret, issue HTTP, open a queue, or create an actor. Empty declarations SHALL remain explicit.

#### Scenario: Secret-bearing network package is inspected
- **WHEN** a candidate declares profile secrets, `secrets.read`, and `network.http`
- **THEN** UI SHALL disclose that granted plaintext can be sent to any HTTPS origin
- **AND** inspection SHALL not access any existing credential

### Requirement: Existing official packages require explicit compatible updates
Official Debug, Diagnostics, Journal, and Keyboard package artifacts that omit newly required manifest declarations SHALL remain immutable and unavailable under the revised exact validator until their owning repositories publish explicit compatible revisions. The host SHALL not rewrite stored bytes, infer empty arrays, or silently execute them through an old parser. Updating one package SHALL not alter another package's source, profile, queue, or provider identity.

#### Scenario: Diagnostics is updated with empty declarations
- **WHEN** its official repository publishes a valid revision explicitly declaring empty profile/resolver/work arrays
- **THEN** ordinary update SHALL restore availability under the revised v1 contract
- **AND** it SHALL acquire no network, secret, profile, or work authority

