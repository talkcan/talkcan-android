## Purpose

Defines GitHub repository resolution, release inspection, trust acknowledgement, and transactional package installation for public Lua v1 channel packages.

## Requirements

### Requirement: Users can resolve a public GitHub repository as an installation source
The application SHALL accept a canonical public GitHub repository URL identifying one `owner/repository`, resolve it through GitHub before any package download, and retain the resolved repository database ID, canonical coordinates, current owner identity, archived state, and source URL as host-domain metadata. Resolution SHALL reject malformed URLs, non-GitHub hosts, extra path components, private or inaccessible repositories, archived repositories for new installation or update, and responses without a positive durable repository ID. The repository URL and mutable coordinates SHALL NOT establish installed provider identity; the host SHALL continue to derive provider identity exclusively from the resolved repository database ID.

#### Scenario: Public repository resolves
- **WHEN** the user submits a canonical URL for an accessible public GitHub repository
- **THEN** the application SHALL resolve its positive repository database ID and canonical coordinates
- **AND** SHALL use that resolved identity in every subsequent release, asset, package-validation, and installation request

#### Scenario: Repository URL is malformed or unsupported
- **WHEN** the user submits a URL for another host, a mutable branch or file path, an issue or release page, or a malformed repository coordinate
- **THEN** the application SHALL reject the input with an actionable source error
- **AND** SHALL NOT query releases, download content, or invoke the installed-package repository

#### Scenario: Repository coordinates now identify another repository
- **WHEN** coordinates previously observed for an installed provider resolve to a different repository database ID
- **THEN** the application SHALL treat the resolved repository as a different provider
- **AND** SHALL NOT update, replace, or inherit the installed provider associated with the earlier repository ID

#### Scenario: Repository was renamed or transferred
- **WHEN** a submitted URL resolves to the same repository database ID under new canonical coordinates
- **THEN** the application SHALL retain the existing installed provider identity
- **AND** SHALL update only observed source and presentation metadata through an explicit successful package transaction

### Requirement: Installation considers only bounded published stable release assets
The application SHALL enumerate a host-bounded set of GitHub releases for the resolved repository and SHALL consider only published, non-draft, non-prerelease releases for the default installation flow. A candidate release SHALL contain exactly one regular release asset with the canonical package asset name `talkcan-channel.zip`; a missing or duplicate canonical asset SHALL make that release ineligible. The host SHALL NOT infer package order from tag text or `packageVersion`; release presentation and default ordering SHALL use GitHub publication metadata with a deterministic release-ID tie-break. Prerelease installation, mutable branch archives, repository source archives, workflow artifacts, and arbitrary release attachments SHALL remain unsupported.

#### Scenario: Repository has compatible stable releases
- **WHEN** bounded release enumeration returns one or more published non-draft stable releases with exactly one canonical package asset
- **THEN** the application SHALL inspect those exact assets statically under package bounds
- **AND** SHALL present only releases whose artifact identity, package format, repository binding, and Lua v1 compatibility validate successfully

#### Scenario: Latest release is incompatible but an older stable release is compatible
- **WHEN** the newest published stable candidate declares an unsupported package or runtime contract and an older bounded candidate validates as compatible
- **THEN** the application SHALL exclude the incompatible candidate and present the older compatible release
- **AND** SHALL NOT execute either artifact during inspection

#### Scenario: Candidate asset is malformed
- **WHEN** a canonical asset is malformed, identity-mismatched, oversized, corrupt, or otherwise invalid
- **THEN** the application SHALL exclude it with a typed inspection result associated with its release
- **AND** SHALL NOT stage, install, or partially publish that artifact

#### Scenario: No compatible stable release exists
- **WHEN** bounded inspection finds no eligible compatible stable package asset
- **THEN** the application SHALL report that no compatible stable release is available
- **AND** SHALL leave installed packages, catalogue definitions, provider snapshots, and runtimes unchanged

### Requirement: Repository and release inspection is bounded, cancellable, and non-executing
GitHub metadata requests and asset inspection SHALL execute off the Android main thread with host-configured finite limits on redirects, response bytes, release count, candidate count, asset bytes, and elapsed operation time. Asset bytes SHALL be streamed through the existing strict package validator without loading unbounded content into memory and without evaluating Lua. Cancellation, timeout, network failure, GitHub rate limiting, malformed JSON, unexpected redirects, or application/service shutdown SHALL terminate the current operation with a typed source outcome and SHALL NOT create a committed package mutation.

#### Scenario: GitHub rate limit is exhausted
- **WHEN** GitHub refuses repository, release, or asset access because the anonymous rate limit is exhausted
- **THEN** the application SHALL expose a typed rate-limited state and any trustworthy retry metadata supplied by GitHub
- **AND** SHALL NOT retry in an unbounded loop or report the repository as nonexistent

#### Scenario: User cancels during inspection
- **WHEN** the user leaves or cancels while release metadata or an asset is being inspected
- **THEN** the host SHALL cancel or drain the bounded source operation
- **AND** SHALL discard incomplete network content without invoking installation

#### Scenario: Asset download redirects
- **WHEN** GitHub returns a bounded HTTPS redirect for an exact public release asset
- **THEN** the client SHALL follow only the configured GitHub release-asset redirect policy
- **AND** SHALL reject protocol downgrade, credential forwarding to an unrelated host, redirect loops, and excess redirects

### Requirement: Publisher trust is derived from resolved immutable owner identity and requires acknowledgement
Before installing or updating code, the application SHALL present the resolved canonical repository, selected release and asset, package presentation metadata, and publisher trust tier. `Official` SHALL be assigned only when the resolved current owner database ID equals the host-pinned official publisher identity. Every other accessible repository SHALL be `Community — Unreviewed`. Mutable owner text, repository topics, package labels, manifest assertions, release names, asset names, and SHA-256 digests SHALL NOT grant Official status. Community installation SHALL require explicit acknowledgement that the repository nominated itself, Talkcan has not reviewed or endorsed it, and the installed trusted Lua code can process values exposed by the current or future channel configuration.

#### Scenario: Official repository is resolved
- **WHEN** the selected repository's resolved owner database ID equals the pinned official publisher identity
- **THEN** the application SHALL display the publisher tier as Official
- **AND** SHALL still display the exact repository, release, asset, and package metadata before mutation

#### Scenario: Community repository is resolved
- **WHEN** the selected repository's owner database ID does not equal the pinned official publisher identity
- **THEN** the application SHALL display `Community — Unreviewed` and the trusted-code warning
- **AND** SHALL require explicit acknowledgement before installation or update

#### Scenario: Manifest or coordinates impersonate the official publisher
- **WHEN** a package label, manifest field, mutable coordinate, or repository name resembles the official publisher but the resolved owner ID differs
- **THEN** the application SHALL classify it as Community — Unreviewed
- **AND** SHALL NOT weaken the warning or identity-mismatch validation

### Requirement: Exact selected assets install only through the existing transactional package repository
After trust acknowledgement, the application SHALL stream the already selected exact release asset and its resolved `PackageSourceRecord` into `InstalledPackagesFacade.installOrUpdate`. The existing validator, content store, index commit, provider publication, and runtime reconciliation SHALL remain authoritative; the GitHub client or UI SHALL NOT write package files, register providers, mutate catalogue definitions, execute Lua, or implement a second update path. A successful first install SHALL make the provider available to the existing generic channel-creation surface without creating an instance automatically. A successful update SHALL apply to every enabled instance of that provider through ordinary provider-revision reconciliation.

#### Scenario: First installation commits
- **WHEN** the user confirms an inspected compatible release and the existing package transaction succeeds
- **THEN** the installed provider SHALL appear in package management and provider selection using the committed revision metadata
- **AND** no Lua state SHALL be created until an enabled matching channel instance is reconciled

#### Scenario: Installation fails before index commit
- **WHEN** network completion, final validation, storage, or index commit fails before a committed package mutation
- **THEN** the application SHALL display the typed failure
- **AND** SHALL preserve the prior index, provider snapshot, catalogue, and live runtime generations

#### Scenario: Update commits a different exact revision
- **WHEN** the user explicitly selects and confirms a compatible release whose digest differs from the installed active revision
- **THEN** the existing transaction SHALL make it active and retain the former active revision as the sole rollback revision
- **AND** runtime replacement SHALL use the existing predecessor-drain-before-successor-ready ordering

#### Scenario: Selected release is already active
- **WHEN** the selected source identity and exact artifact digest equal the active installed revision
- **THEN** installation SHALL return the existing idempotent result
- **AND** SHALL NOT create another index, rollback, provider, or runtime generation

### Requirement: Installed package management exposes committed state and explicit mutations
The application SHALL expose a host-rendered package-management surface listing each committed provider's canonical repository, trust tier, active package version, active release tag, and availability or typed failure. It SHALL expose manual refresh for compatible releases, explicit update selection, rollback only when one retained rollback exists, and removal. Package management SHALL derive state from the coordinator and committed installed index rather than UI-local assumptions. It SHALL serialize conflicting mutations, disable invalid actions while a mutation is active, and reconcile state after commit-before-publication failure or service restart.

#### Scenario: Installed package is healthy
- **WHEN** the committed active artifact validates and its provider snapshot is published
- **THEN** package management SHALL show the active revision and available actions
- **AND** the existing catalogue management surface SHALL offer that provider for instance creation

#### Scenario: Explicit rollback succeeds
- **WHEN** the user confirms rollback and one retained revision exists
- **THEN** package management SHALL invoke `InstalledPackagesFacade.rollback` for the durable repository identity
- **AND** SHALL display the exact newly active and retained rollback revisions after committed publication

#### Scenario: User removes an installed provider
- **WHEN** the user confirms package removal
- **THEN** package management SHALL invoke the existing atomic removal operation
- **AND** SHALL explain that existing channel definitions will remain preserved but unavailable

#### Scenario: Service is unavailable during a requested mutation
- **WHEN** the foreground service or package coordinator is unavailable, closing, or closed
- **THEN** package management SHALL reject or disable the mutation with an actionable lifecycle state
- **AND** SHALL NOT retain a UI-only pending mutation that executes after an unrelated service generation starts

### Requirement: GitHub installation stores no credentials and performs no automatic mutation
The initial GitHub installation client SHALL access public repository and release data without persisting a GitHub token, cookie, password, or credential. It SHALL perform no background installation, update, rollback, or removal without an explicit current user action and trust confirmation. Refresh checks MAY retrieve bounded public metadata while the package-management surface is active, but SHALL NOT download executable assets or mutate the installed index until the user selects and confirms an exact release.

#### Scenario: Application restarts with an installed package
- **WHEN** the service starts with a committed package installed from GitHub
- **THEN** it SHALL recover and materialize that exact local artifact through the existing package store
- **AND** SHALL NOT contact GitHub merely to make the installed provider available

#### Scenario: New release exists remotely
- **WHEN** a manual refresh discovers a newer compatible stable release
- **THEN** package management SHALL present it as an available explicit update
- **AND** SHALL NOT download or activate it automatically