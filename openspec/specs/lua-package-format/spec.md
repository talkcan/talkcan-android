## Purpose

Defines the structure, validation constraints, manifest metadata, and integrity
checks for Lua channel packages in ZIP format.
## Requirements
### Requirement: Lua package format v1 is a strict source-only archive
A Lua channel package SHALL be one ZIP artifact containing exactly one root `manifest.json` regular file and one or more regular Lua source files below `lua/`. Every accepted source path SHALL have the form `lua/<segment>(/<segment>)*.lua`, where each segment matches `[a-z][a-z0-9_]*`; the host SHALL derive the module name by removing `lua/` and `.lua` and replacing `/` with `.`. The archive SHALL NOT contain bytecode, native libraries, assets, executable files, symlinks, hard-link metadata, encrypted entries, unsupported compression methods, non-regular entries, or unrecognized regular files. Package-format v1 SHALL accept only stored and deflated regular entries. Because portable ZIP regular-entry metadata does not encode a filesystem inode or link count, byte-identical regular entries SHALL be validated independently and SHALL NOT be classified as filesystem hard links without explicit link metadata. Validation SHALL reject the complete artifact rather than ignore an unexpected entry.

#### Scenario: Valid package maps files to modules
- **WHEN** an artifact contains `manifest.json`, `lua/main.lua`, and `lua/client/http.lua` with valid v1 content
- **THEN** the host SHALL derive the modules `main` and `client.http`
- **AND** it SHALL construct the immutable source map only after every archive entry validates

#### Scenario: Archive contains an unexpected file
- **WHEN** a package contains `assets/icon.png`, `README.md`, a native library, or another regular file outside the v1 manifest and Lua-source layout
- **THEN** the host SHALL reject the complete package with a typed unexpected-entry result
- **AND** it SHALL NOT ignore, extract, store as active, register, or execute the unexpected content

#### Scenario: Archive contains an unsupported entry type or compression feature
- **WHEN** a package contains a symbolic link, explicit hard-link metadata, another non-regular entry, an encrypted entry, an unsupported compression method, or metadata that cannot be accounted for safely
- **THEN** the host SHALL reject the complete package before activation
- **AND** it SHALL NOT follow the link, decrypt the entry, or attempt a permissive fallback

### Requirement: Package manifest declares one bounded provider revision
`manifest.json` SHALL be a UTF-8 JSON object with exactly `manifestVersion`, `repositoryId`, `packageVersion`, `entryModule`, `presentation`, `runtime`, `configuration`, `resources`, `profileTypes`, `choiceResolvers`, `workQueues`, and `capabilities`. `manifestVersion` SHALL be integer `1`; `repositoryId` a positive decimal string; `packageVersion` nonblank and bounded; `entryModule` canonical; presentation labels bounded; and runtime Lua/API versions exact. Configuration, resources, profile types, choice resolvers, work queues, and capabilities SHALL use their referenced exact contracts. Every field SHALL have the declared JSON type. V1 validation SHALL reject missing fields, duplicate keys, unknown fields, blank/invalid identity, invalid module references, duplicates across each declaration namespace, undeclared resolver/work capabilities, and values exceeding finite bounds. Because v1 is unreleased and evolves by clean cutover, the host SHALL reject superseded shapes omitting any newly required array/object; it SHALL not infer empty declarations, dispatch a legacy parser, negotiate features, or change `talkcan-lua-v1`.

#### Scenario: Manifest declares all required v1 fields
- **WHEN** a manifest contains exactly the revised fields with valid configuration, resources, profile types, choice resolvers, work queues, and capabilities
- **THEN** the host SHALL retain every validated declaration in one immutable package revision
- **AND** no declaration SHALL execute Lua or acquire authority during validation

#### Scenario: Manifest contains an unknown field
- **WHEN** a v1 manifest contains a field outside the exact revised key set
- **THEN** the host SHALL reject the complete package
- **AND** it SHALL not preserve or reinterpret the field

#### Scenario: Manifest version is unsupported
- **WHEN** `manifestVersion` is absent or not exactly `1`
- **THEN** the host SHALL return a typed unsupported-format result
- **AND** it SHALL not execute or reinterpret the package

#### Scenario: Manifest declares empty optional facilities
- **WHEN** a package needs no configuration, resources, profiles, resolvers, queues, or capabilities and declares each required object/array explicitly empty
- **THEN** validation SHALL accept those exact empty declarations
- **AND** materialization SHALL grant none of those facilities

#### Scenario: Historical package omits a new declaration
- **WHEN** an otherwise valid development artifact omits `profileTypes`, `choiceResolvers`, or `workQueues`
- **THEN** the revised host SHALL reject it before installation, publication, or execution
- **AND** official packages SHALL require republished exact artifacts rather than a compatibility default

#### Scenario: Manifest declares invalid capability identifier
- **WHEN** a manifest contains an identifier outside the stable allowlist, whose values are exactly `audio.transcription`, `audio.synthesis`, `audio.playback`, `audio.files`, `storage.files`, `keyboard.output`, `network.http`, `profiles.read`, `secrets.read`, and `work.queue`
- **THEN** the host SHALL reject the complete package with a typed capability-validation error
- **AND** it SHALL not register the provider or authorize execution

#### Scenario: Manifest contains duplicate capability identifiers
- **WHEN** a capability identifier occurs more than once
- **THEN** validation SHALL reject the complete package

### Requirement: Provider identity is bound to host-resolved durable repository identity
Every package-validation request SHALL include a host-resolved durable GitHub repository identity and exact release-asset source record. The manifest `repositoryId` SHALL match the resolved repository database ID exactly. The host SHALL derive the stable channel implementation ID as `github-repository:<repositoryId>` from the resolved identity. Mutable `owner/repository` coordinates, labels, summaries, package filenames, release tags, asset names, package versions, and artifact digests SHALL NOT establish or change provider identity. The `builtin:` and `internal:` namespaces and every host-reserved namespace SHALL remain unavailable to installed packages.

#### Scenario: Manifest identity matches resolved repository
- **WHEN** the manifest repository ID equals the host-resolved repository database ID
- **THEN** the host SHALL derive the installed provider ID from that durable repository ID
- **AND** repository coordinates SHALL remain presentation and link metadata only

#### Scenario: Manifest self-asserts another repository
- **WHEN** the manifest repository ID differs from the host-resolved expected repository identity
- **THEN** the host SHALL reject the complete package with a typed identity-mismatch result
- **AND** it SHALL NOT register the asserted provider or rewrite the manifest identity

#### Scenario: Repository coordinates change
- **WHEN** the same durable repository identity is later resolved under different owner or repository coordinates
- **THEN** the provider implementation ID SHALL remain unchanged
- **AND** existing channel instances SHALL continue to reference the same provider identity

### Requirement: Module names and paths are canonical and collision free
The package validator SHALL normalize and validate every archive name before reading it as package content. Absolute paths, `.` or `..` segments, empty or repeated segments, backslashes, trailing separators on regular files, percent-encoded separators, NUL bytes, noncanonical Unicode, uppercase module aliases, duplicate names, and case-folding collisions SHALL reject the complete artifact. `entryModule` SHALL match the canonical module grammar used by Lua Runtime v1 and SHALL resolve to exactly one included source file. The validator SHALL NOT extract package entries through their archive-supplied paths.

#### Scenario: Entry path attempts traversal
- **WHEN** an artifact contains `../main.lua`, `lua/../../main.lua`, an absolute path, or a backslash-based traversal spelling
- **THEN** the host SHALL reject the complete package before storing or reading that entry as source
- **AND** no file SHALL be created outside private staging

#### Scenario: Entries collide after canonical comparison
- **WHEN** two entries are duplicate names or differ only by a prohibited case or normalization variant
- **THEN** the host SHALL reject the complete package with a typed collision result
- **AND** it SHALL NOT choose one entry by ZIP order

#### Scenario: Entry module is missing
- **WHEN** the manifest declares `entryModule` as `main` but the artifact does not contain exactly one canonical `lua/main.lua`
- **THEN** validation SHALL fail before program-image construction
- **AND** no fallback entry module SHALL be selected

### Requirement: Package validation is bounded, static, and fail closed
Before an artifact can enter the immutable content store or installed-provider index, the host SHALL enforce finite host-configured bounds on exact compressed artifact bytes, archive-entry count, manifest bytes, path bytes, per-module uncompressed source bytes, total uncompressed source bytes, and compression expansion. It SHALL decode manifest and source as strict UTF-8, reject Lua binary-chunk signatures, statically validate configuration and capability schemas and defaults, and pass the derived entry module, source map, and exact Lua/API requirements through the existing `ImmutableProgramImage` validation. Package validation SHALL NOT execute Lua, create a Lua state or actor, call a plugin callback, acquire a channel capability, mutate the provider registry, mutate the channel catalogue, or perform network access.

#### Scenario: Package exceeds an archive bound
- **WHEN** compressed bytes, entry count, one expanded source, total expanded source, path length, or expansion ratio exceeds its configured bound
- **THEN** validation SHALL terminate with a typed bounds result
- **AND** it SHALL NOT retain a partial source map, active content, or growing overflow buffer

#### Scenario: Source is invalid UTF-8 or bytecode
- **WHEN** a Lua entry contains invalid UTF-8 or begins with a Lua binary-chunk signature
- **THEN** the host SHALL reject the complete artifact before state creation
- **AND** it SHALL NOT coerce text, load bytecode, or continue with other modules

#### Scenario: Validation receives adversarial but syntactically valid Lua
- **WHEN** package source contains code that would loop, allocate, log, spawn, sleep, or request another host effect if executed
- **THEN** static package validation SHALL complete without executing that source
- **AND** no Lua state, actor, timer, log, callback, or host effect SHALL be observed

#### Scenario: Static configuration validation executes no Lua
- **WHEN** the host validator checks manifest configuration schemas, UI settings, and defaults during package installation
- **THEN** validation SHALL complete using only static host logic
- **AND** no Lua engine execution, module loading, or script state allocation SHALL occur

#### Scenario: Invalid default configuration value fails validation
- **WHEN** a manifest configuration schema declares a default value that does not match the field type or violates data bounds
- **THEN** validation SHALL fail-closed and reject the package revision

### Requirement: Exact artifact digest provides integrity but not publisher authentication
The host SHALL compute SHA-256 over the exact artifact bytes while staging and SHALL use the lowercase digest as immutable content identity. A stored revision SHALL retain the exact digest and exact host-resolved repository, release, and asset source record. Materialization after process start SHALL revalidate the stored bytes against the committed digest before constructing a provider. Digest validation SHALL detect corruption or substitution relative to the installed index, but the host SHALL NOT describe digest-only content as publisher-signed, publisher-verified, reviewed, endorsed, or authenticated.

#### Scenario: Exact artifact is staged successfully
- **WHEN** the complete staged artifact passes all validation
- **THEN** the host SHALL record the SHA-256 of those exact bytes as the revision fingerprint
- **AND** any identical artifact SHALL produce the same fingerprint

#### Scenario: Stored content no longer matches its digest
- **WHEN** committed content bytes differ from the digest referenced by the installed index
- **THEN** the host SHALL refuse to materialize or execute that revision
- **AND** it SHALL report typed corruption without silently choosing another revision

#### Scenario: Digest is presented in diagnostics
- **WHEN** the host reports package integrity or revision information
- **THEN** it MAY report a bounded digest or digest prefix as content identity
- **AND** it SHALL NOT claim that SHA-256 alone authenticates the package publisher

### Requirement: New manifest declarations are statically cross-validated
The package validator SHALL validate profile-type schemas, choice-resolver IDs/modules/capability subsets, work-queue IDs, configuration source references, required module presence, and capability relationships as one bounded graph. Every package dynamic source SHALL reference a resolver or profile type declared by the same repository revision; every resolver capability SHALL be a permitted subset of package capabilities; `workQueues` SHALL be nonempty only with `work.queue`; and secret profile fields SHALL require a package capable of reading secrets before runtime use. Cross-package declaration references and unresolved aliases SHALL be rejected. Static cross-validation SHALL not create a Lua state, profile, secret, queue, HTTP request, or editor object.

#### Scenario: Resolver reference is missing
- **WHEN** configuration names a package resolver absent from `choiceResolvers`
- **THEN** static validation SHALL reject the complete package
- **AND** it SHALL not defer the error to editor runtime

#### Scenario: Queue lacks work capability
- **WHEN** `workQueues` contains an ID but `capabilities` omits `work.queue`
- **THEN** validation SHALL reject the complete package before storage

### Requirement: Existing official packages cut over to explicit empty declarations
Every official package published against the revised v1 contract SHALL include explicit `profileTypes`, `choiceResolvers`, and `workQueues`, even when empty. Stored predecessor artifacts that omit them SHALL remain exact immutable bytes and become unavailable under the revised validator until an explicit compatible update is installed. The host SHALL not rewrite their manifests or source maps in place.

#### Scenario: Existing official package is updated
- **WHEN** Debug, Diagnostics, Journal, or Keyboard publishes a compatible revision with explicit empty new arrays
- **THEN** ordinary installation/update SHALL validate and activate it under the same package path
- **AND** it SHALL acquire no new authority from empty declarations

