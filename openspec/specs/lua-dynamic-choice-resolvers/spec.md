# lua-dynamic-choice-resolvers Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: Packages declare bounded dynamic-choice resolvers statically
A package manifest SHALL contain an exact bounded `choiceResolvers` array. Each declaration SHALL contain a unique canonical package-local `id`, canonical package-local `module`, and an exact capability subset drawn from the package's own declarations and the resolver allowlist. A configuration `dynamic-choice` source MAY reference a resolver declared by the same package and MAY name one earlier scalar dependency according to the existing configuration contract. Every module SHALL exist in the immutable source map. Unknown keys, duplicate IDs, undeclared capabilities, unresolved modules, cycles, or cross-package resolver references SHALL reject the complete package without executing Lua.

#### Scenario: Model resolver is declared
- **WHEN** a package declares resolver `models`, module `openai.model_choices`, and eligible profile/secret/HTTP capabilities
- **THEN** static validation SHALL retain that exact declaration and permit a configuration source to reference it
- **AND** validation SHALL not load the module or issue a request

#### Scenario: Resolver requests undeclared capability
- **WHEN** a resolver declaration names `network.http` but the package did not declare it
- **THEN** validation SHALL reject the complete package before installation

### Requirement: Resolver modules expose one exact callback contract
When invoked, the declared resolver module SHALL be loaded in a fresh restricted Lua state from the package's immutable source map. Its module chunk SHALL execute synchronously under the existing source-load effect guard and return a plain table with exactly one function at key `resolve`. The host SHALL call `resolve(request)` exactly once. `request` SHALL be a detached exact-key table containing `schema_version=1`, `resolver`, optional `dependency` with exact `field` and scalar `value`, and optional selected `profile` ID when the dependency is a profile reference. The callback SHALL return exactly `({choices = <array>}, nil)` or `(nil, error_table)`, where every choice is exact `{value=<bounded nonblank string>, label=<bounded nonblank string>}`.

#### Scenario: Resolver returns model choices
- **WHEN** the callback returns unique bounded model IDs and labels
- **THEN** the host SHALL validate the entire result and publish it atomically to the requesting editor/readiness operation

#### Scenario: Resolver returns malformed choices
- **WHEN** choices contain unknown keys, duplicate values or labels, blank/invalid strings, mixed tables, excessive entries, or a malformed error
- **THEN** the invocation SHALL fail with a typed resolver-result error
- **AND** no partial choice list SHALL be published

### Requirement: Dynamic resolvers execute in isolated short-lived actors
Each invocation SHALL create or acquire a fresh resolver actor and independent Lua state that is not a channel runtime generation, does not call channel `startup`, and shares no globals, module cache, tasks, conversation, queue/job handles, profile userdata, or mutable Lua values with any runtime or sibling resolver. The host SHALL close the state after one terminal result. A package update, rollback, removal, profile/configuration change, caller cancellation, deadline, or shutdown SHALL invalidate the invocation and suppress late publication.

#### Scenario: Two editors resolve concurrently
- **WHEN** two instances invoke the same package resolver concurrently
- **THEN** each invocation SHALL have independent state and authority
- **AND** mutation or failure in one SHALL not affect the other

#### Scenario: Package revision changes during resolution
- **WHEN** the active package revision is replaced before a resolver completes
- **THEN** the predecessor result SHALL be stale and unpublished
- **AND** a later request SHALL use only the successor's declared module and source map

### Requirement: Resolver authority is narrower than runtime authority
A resolver SHALL receive only pure Lua/module functions, bounded logging, `talkcan.json`, package-local profile resolution for the one selected profile, `talkcan.secrets` for that profile when declared, and `talkcan.http` when declared. It SHALL NOT receive channel lifecycle callbacks, runtime `spawn`, `defer`, or `sleep`, durable work, audio, filesystem mounts, keyboard output, channel selection, instance resources unrelated to the request, or another profile. Requiring an injected but ineligible module SHALL not grant its operations.

#### Scenario: Resolver discovers models
- **WHEN** a declared resolver resolves its selected profile, reads its granted API key, and requests the profile's HTTPS models endpoint
- **THEN** those operations SHALL run under one resolver owner and deadline
- **AND** no channel actor, work item, audio route, or keyboard effect SHALL be created

#### Scenario: Resolver attempts durable work
- **WHEN** resolver code calls `talkcan.work`
- **THEN** the operation SHALL return `E_INVALID_CONTEXT` or `E_CAPABILITY_UNDECLARED`
- **AND** no queue record SHALL be created

### Requirement: Resolver execution is bounded and fail closed
The host SHALL enforce finite resolver source/load instructions, active instructions, memory, wall-clock deadline, suspended HTTP/secret/profile operations, aggregate request/result bytes, log rate, concurrent invocations per package, and process-wide invocations. Choice arrays SHALL NOT have a separate count limit; they remain bounded by aggregate result bytes and per-value/label byte limits. No generic retry loop SHALL be automatic. Timeout, cancellation, quota, Lua failure, module failure, capability denial, profile unavailability, HTTP failure, and malformed output SHALL become typed unavailable results associated with the requesting field and dependency. A prior valid choice set MAY be displayed only as explicitly stale and SHALL not authorize readiness or effect execution.

#### Scenario: Resolver exceeds deadline
- **WHEN** Lua or one of its allowed operations does not finish before the resolver deadline
- **THEN** the host SHALL close the resolver actor and return a typed timeout
- **AND** late transport or Lua completion SHALL not publish choices

#### Scenario: Resolver capacity is exhausted
- **WHEN** admission would exceed an applicable invocation bound
- **THEN** the host SHALL return a typed busy/unavailable result before state creation
- **AND** existing invocations SHALL remain isolated

### Requirement: Editor and readiness resolve exact dependency revisions
Every resolver request SHALL bind the active package revision, resolver declaration, channel draft or persisted dependency scalar, selected profile revision when applicable, and caller request identity. Editor resolution SHALL clear transitive dependent draft values when an upstream dependency changes. Readiness resolution SHALL validate the persisted selected choice against a successful current result and SHALL not treat an editor result as permanent authorization. Runtime effects SHALL independently revalidate selected profile and capabilities at call time.

#### Scenario: Profile changes while model choices are visible
- **WHEN** the user selects another profile in a channel draft
- **THEN** the editor SHALL clear the model draft before invoking the resolver for the new profile
- **AND** a late result for the old profile SHALL not populate the new field

#### Scenario: Selected model disappears
- **WHEN** current model resolution succeeds without the persisted model ID
- **THEN** readiness SHALL report the model reference unavailable while preserving the configured ID
- **AND** the host SHALL not substitute another choice

### Requirement: Resolver diagnostics exclude profile and provider content
Resolver diagnostics SHALL identify package revision, resolver ID, requesting field, phase, bounded counts, capability category, and normalized terminal outcome. They SHALL NOT contain profile scalar values not intended for UI, secrets, URLs beyond bounded non-sensitive classification, HTTP headers/bodies, choice values or labels where sensitive, provider exceptions, model payloads, or Lua stack locals. Package-authored logs remain subject to normal structured-log bounds and the prohibition on logging secrets.

#### Scenario: Resolver HTTP request fails
- **WHEN** the provider endpoint returns an error or malformed response
- **THEN** the UI SHALL receive a bounded package-defined or normalized unavailable reason
- **AND** host diagnostics SHALL not contain the credential or response body

