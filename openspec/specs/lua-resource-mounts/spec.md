## Purpose

Defines package-declared, instance-bound directory-tree mounts: manifest declarations, host-rendered selection, opaque persisted grants, generation-owned Lua mount handles, availability projection, update compatibility, and a platform-neutral reauthorization lifecycle.

## Requirements

### Requirement: Packages declare exact bounded directory-tree resources
A package manifest SHALL contain a `resources` object with exactly one `mounts` array. Every mount declaration SHALL contain exactly canonical `id`, `kind`, `access`, `required`, bounded nonblank `label`, and optional bounded `help`. V1 SHALL accept only `kind="directory-tree"`, `access="read-write"`, and `required=true`; mount IDs SHALL match `[a-z][a-z0-9_]*` and be unique. An empty mounts array SHALL be valid. The validator SHALL reject unknown keys, duplicate IDs, unsupported values, invalid UTF-8, and declarations exceeding finite host bounds before installation or provider publication.

#### Scenario: Package declares one writable directory tree
- **WHEN** a manifest declares one canonical bounded mount with kind `directory-tree`, access `read-write`, and `required=true`
- **THEN** package validation SHALL retain that declaration in the immutable validated revision
- **AND** provider materialization SHALL expose it to the generic resource editor without executing Lua

#### Scenario: Package declares no mounts
- **WHEN** a manifest contains `resources.mounts` as an empty array
- **THEN** validation SHALL succeed
- **AND** the materialized provider SHALL require no user-selected storage resource

#### Scenario: Mount declaration is malformed
- **WHEN** a mount has an unknown key, duplicate or noncanonical ID, unsupported kind/access, `required=false`, blank label, invalid UTF-8, or over-bound value
- **THEN** the host SHALL reject the complete package before storage, registration, or execution

### Requirement: Mount bindings are host-owned instance resources
The host SHALL persist each selected mount as an opaque binding keyed by channel instance ID, provider implementation ID, and declared mount ID. A binding SHALL retain the declared kind/access, opaque platform grant, and current status independently of scalar channel configuration and runtime generations. The platform grant SHALL NOT enter the catalogue payload, installed-package index, provider object, Lua values, logs, evidence, or public error detail. Separate instances SHALL have isolated bindings even when they use the same provider.

#### Scenario: User binds a required mount
- **WHEN** the user selects a directory tree for one instance's declared mount
- **THEN** the host SHALL persist an opaque binding for that exact instance, provider, and declaration ID
- **AND** another instance SHALL NOT acquire that binding by declaring the same mount ID

#### Scenario: Process restarts after selection
- **WHEN** the application restarts with a valid persisted platform grant
- **THEN** the host SHALL restore the binding and make it available to the reconstructed instance
- **AND** no Lua state or prior-generation mount handle SHALL be restored

### Requirement: The generic editor owns mount selection and repair
The host-rendered channel create/edit surface SHALL display each declared mount using package-provided label/help metadata and a host-owned system directory-tree picker. The picker SHALL request only the declared access, persist the platform grant, validate usable read/write access, and commit the binding through the host resource store. A required missing, revoked, stale, read-only, or otherwise unusable binding SHALL make the instance explicitly unavailable without replacing scalar configuration or silently selecting another directory. The editor SHALL permit explicit reselection.

#### Scenario: Required mount is missing
- **WHEN** an instance has valid scalar configuration but no binding for a required mount
- **THEN** the instance SHALL project resource unavailability
- **AND** the host SHALL NOT construct an authorized runtime that can access ambient storage

#### Scenario: User repairs a revoked binding
- **WHEN** a required mount reports `needs-reauthorization` and the user selects a usable replacement tree
- **THEN** the host SHALL atomically commit the new binding and reconcile the instance to a fresh runtime generation
- **AND** predecessor mount handles SHALL remain revoked

### Requirement: Runtime mount handles are opaque and generation-owned
`talkcan.fs.mount(id)` SHALL synchronously return `(mount_userdata, nil)` only for a mount declared by the current provider and bound to the current instance with usable access. The userdata SHALL have a locked metatable and contain only a state-local unforgeable token. Its host registry entry SHALL bind it to exactly one Lua state, instance, generation, declaration, and access set. Lua MAY retain the handle across callbacks and managed tasks in that generation, but SHALL NOT inspect, serialize, log, transfer to another state, or use it after generation close. Mount lookup during source/module evaluation SHALL fail through the effect-call-during-load guard.

#### Scenario: Startup acquires a declared mount
- **WHEN** authorized `startup` calls `fs.mount("output")` for an available declared binding
- **THEN** it SHALL receive a generation-owned mount userdata without a platform path, URI, URL, bookmark, or document identifier

#### Scenario: Package requests an undeclared mount
- **WHEN** Lua calls `fs.mount` with an ID absent from its validated resource declaration
- **THEN** the call SHALL return `E_CAPABILITY_UNDECLARED` before platform access

#### Scenario: Predecessor handle is used after replacement
- **WHEN** a generation is replaced and code attempts to use its former mount handle
- **THEN** the host SHALL reject it with `E_CLOSED` or `E_STALE`
- **AND** it SHALL perform no storage operation

### Requirement: Resource availability is projected without granting authority
The host SHALL expose every declared mount ID exactly once in `handle_readiness` at `context.resources.mounts[id]` with value `available`, `read-only`, `needs-reauthorization`, or `unavailable`. The readiness snapshot SHALL contain no platform grant or diagnostic detail. A package readiness result SHALL NOT grant authority; every filesystem and audio-file operation SHALL revalidate the live binding, access, instance, generation, capability declaration, and handle.

#### Scenario: Readiness observes a valid mount
- **WHEN** a declared read-write binding remains usable
- **THEN** readiness context SHALL report that mount as `available`
- **AND** the host SHALL still revalidate it when an operation begins

#### Scenario: Platform grant is revoked after readiness
- **WHEN** readiness previously reported `available` but the user or provider revokes access before an operation
- **THEN** the operation SHALL fail with `E_REAUTHORIZATION_REQUIRED` or `E_MOUNT_UNAVAILABLE`
- **AND** it SHALL NOT rely on the cached readiness result

### Requirement: Package updates preserve only compatible resource bindings
A provider update SHALL retain a mount binding only when provider identity, declaration ID, kind, and requested access remain compatible. A removed or incompatible declaration SHALL make the binding dormant and unavailable while preserving it for an explicit package rollback. Removing a channel instance SHALL release all of its bindings and SHALL release an underlying platform permission only when no remaining binding references that grant.

#### Scenario: Compatible update retains mount
- **WHEN** an updated revision declares the same mount ID, kind, and access
- **THEN** the successor generation SHALL use the existing persisted binding after live validation

#### Scenario: Update removes a mount declaration
- **WHEN** an updated revision no longer declares a previously bound mount
- **THEN** Lua SHALL NOT receive or access that mount
- **AND** rollback to a compatible retained revision SHALL be able to restore the preserved binding

### Requirement: Mount semantics are platform neutral
The public resource contract SHALL describe logical directory trees, opaque grants, access, and portable status only. It SHALL NOT expose Android paths, URI schemes, document IDs, `ContentResolver`, `DocumentFile`, iOS URLs, security-scoped bookmark bytes, file-provider objects, file descriptors, SDK exceptions, or provider account/device identity. Android SHALL implement new mounts through persisted Storage Access Framework tree grants; a future platform adapter MAY map the same contract to its native document-tree authority without changing packages.

#### Scenario: Android mount is selected
- **WHEN** Android returns a document-tree URI from the system picker
- **THEN** the Android adapter SHALL persist and use it behind the opaque binding
- **AND** Lua SHALL observe only the declared mount ID, portable status, and mount userdata
