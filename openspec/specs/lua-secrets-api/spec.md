# lua-secrets-api Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: Packages declare secret-read eligibility explicitly
A Lua package SHALL obtain plaintext secret authority only by declaring the exact public capability identifier `secrets.read` in its validated manifest. The host SHALL reserve and inject `talkcan.secrets` for every Lua state and resolver state, but requiring the module SHALL NOT grant authority. Secret authority SHALL apply only to protected references explicitly granted through a selected package-owned profile. A package without the capability or without a matching grant SHALL receive a typed denial before protected storage access.

#### Scenario: Eligible package requires secrets
- **WHEN** a package declaring `secrets.read` requires `talkcan.secrets`
- **THEN** the runtime SHALL return the injected module
- **AND** later reads SHALL remain restricted to profile-bound grants owned by that package

#### Scenario: Undeclared package reads a secret
- **WHEN** a package without `secrets.read` attempts to resolve a reference
- **THEN** the call SHALL return `E_CAPABILITY_UNDECLARED`
- **AND** protected storage SHALL not be queried

### Requirement: Profile secret fields persist references rather than values
A package-declared profile type MAY declare a field of exact type `secret`. The generic profile editor SHALL collect its value through a protected control, store the plaintext only in host-owned protected credential storage, and persist a stable non-secret reference in the profile document. Profile snapshots, channel configuration, installed-package metadata, durable work, dynamic-choice results, logs, diagnostics, backups not explicitly designated for credentials, and package archives SHALL NOT contain the plaintext. Replacing or clearing a secret SHALL update protected storage and profile reference state atomically or report a typed failure without committing a partial profile.

#### Scenario: User saves profile secret
- **WHEN** a user enters a valid secret for a package profile and saves it
- **THEN** protected storage SHALL contain the value under a host-owned reference
- **AND** the profile metadata SHALL contain only non-secret presence/reference state

#### Scenario: Secret replacement fails
- **WHEN** protected storage cannot commit a replacement
- **THEN** the host SHALL preserve the prior committed profile and secret reference
- **AND** no partial new value SHALL become runtime-visible

### Requirement: `talkcan.secrets` exposes only bounded plaintext resolution
`talkcan.secrets` SHALL expose exactly `read(reference)`. The argument SHALL be an opaque state-local secret-reference value returned by `talkcan.profiles`; strings, profile IDs, guessed aliases, raw keystore names, and foreign-state references SHALL be rejected. A successful call SHALL return `(plaintext_string, nil)` with a bounded valid-UTF-8 secret. Failure SHALL return a normalized error distinguishing unavailable, denied, stale, closed, and protected-storage failure without revealing whether an ungranted reference exists. The module SHALL expose no enumerate, create, replace, delete, export, keystore, key-generation, encryption, or platform APIs.

#### Scenario: Lua reads granted profile secret
- **WHEN** an eligible live owner passes a current reference from its selected package profile
- **THEN** `read` SHALL return the exact bounded plaintext to that Lua state
- **AND** the host SHALL not insert it into configuration, logs, or diagnostics

#### Scenario: Lua guesses another reference
- **WHEN** Lua passes a string or foreign opaque reference
- **THEN** `read` SHALL return `E_INVALID_ARGUMENT`, `E_DENIED`, or `E_STALE` without protected-value disclosure
- **AND** it SHALL not reveal whether the target exists

### Requirement: Plaintext access is an explicit exfiltration authority
A package granted both `secrets.read` and `network.http` SHALL be technically capable of transmitting every profile secret granted to it to any HTTPS origin. Installation, profile creation, and capability presentation SHALL state this authority without claiming origin confinement or automatic redaction inside Lua. The host SHALL NOT inspect provider payloads to prevent intentional use, silently substitute opaque credentials, or claim that plaintext remains host-only after a successful read.

#### Scenario: User grants secret and network authority
- **WHEN** a package declares both capabilities and a user binds a profile secret
- **THEN** the host SHALL present that the package can read and send the value over arbitrary HTTPS
- **AND** successful plaintext delivery to Lua SHALL be treated as intentional authorization

### Requirement: Secret reads are context, generation, and package bound
Secret resolution SHALL be allowed only from a live host-managed input owner, managed-task owner, durable-work effect owner, or dynamic-choice resolver owner whose package and selected profile establish the grant. Source evaluation SHALL fail through the effect guard. Startup, lifecycle, readiness, SOS, unmanaged coroutines, stale generations, and unrelated packages SHALL be denied before plaintext materialization. A profile edit, deletion, package replacement/removal, configuration replacement selecting another profile, or generation close SHALL revoke predecessor references; already returned Lua strings cannot be revoked and SHALL die only with Lua reachability/state teardown.

#### Scenario: Resolver reads selected profile secret
- **WHEN** a bounded package resolver receives authority for one selected profile
- **THEN** it MAY resolve only secret references from that profile
- **AND** resolver termination SHALL invalidate its opaque references and close its state

#### Scenario: Profile changes during active generation
- **WHEN** a selected profile or protected value is replaced
- **THEN** the host SHALL retire affected runtime/resolver authority according to generation policy
- **AND** predecessor opaque references SHALL fail as stale if used again

### Requirement: Secret content is excluded from host observability and durable APIs
The host SHALL never include resolved plaintext in operation labels, structured logs emitted by host adapters, crash-safe work records, profile metadata, dynamic choices, channel snapshots, exceptions returned to Lua, package provenance, analytics, or acceptance evidence. Diagnostics MAY include package/instance/profile-type attribution, field ID, byte length, phase, and normalized outcome. Lua-supplied logs or durable values that happen to contain copied plaintext cannot be reliably identified by content; the public contract SHALL prohibit plugins from persisting or logging secrets and SHALL not claim content-scanning protection.

#### Scenario: Secret read fails
- **WHEN** protected storage returns an internal exception
- **THEN** Lua and diagnostics SHALL receive only a normalized non-secret failure
- **AND** no credential bytes or platform aliases SHALL be formatted

#### Scenario: Plugin copies secret into work payload
- **WHEN** plugin code attempts to submit a copied plaintext string as ordinary durable work data
- **THEN** ordinary type validation MAY accept the string because plaintext has no durable taint
- **AND** the package contract SHALL classify that behavior as a plugin security violation rather than claim host redaction

### Requirement: Secret sizes, reads, and retained plaintext are bounded
The host SHALL enforce finite per-secret UTF-8 bytes, per-owner read count, concurrent protected-storage operations, and temporary host-buffer lifetime. Oversized, corrupt, unavailable, or non-UTF-8 protected values SHALL fail atomically. Host temporary buffers SHALL be cleared or released promptly where platform APIs permit. Secret quotas and errors SHALL not allow one package to deny access to unrelated profiles indefinitely.

#### Scenario: Protected value exceeds bound
- **WHEN** a stored value exceeds the runtime plaintext bound
- **THEN** `read` SHALL return `E_TOO_LARGE` without a partial string
- **AND** the owning Lua state SHALL remain usable

