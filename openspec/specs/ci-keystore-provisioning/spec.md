# ci-keystore-provisioning Specification

## Purpose

Define how CI provisions the Android debug keystore before signing-sensitive
Gradle tasks. Trusted runs decode the repository secret; fork pull requests
without access to repository secrets generate an ephemeral debug keystore.

## Requirements

### Requirement: CI SHALL provision the debug keystore before invoking gradle

The CI workflow SHALL provision `.android/debug.keystore` (relative to the
repository root, matching `rootProject.file(".android/debug.keystore")` in
`app/build.gradle.kts`) before any gradle task that triggers signing
validation runs. Trusted runs SHALL decode the
`ANDROID_DEBUG_KEYSTORE_BASE64` secret. A `pull_request` whose head repository
differs from `github.repository` SHALL generate an ephemeral debug keystore
when the secret is unavailable. The provisioning step SHALL fail if the
resulting file is empty or missing.

#### Scenario: Secret-backed debug keystore on trusted runs

- **WHEN** the CI workflow runs on a same-repository `pull_request`, `push` to
  `main`, or `workflow_dispatch` with `ANDROID_DEBUG_KEYSTORE_BASE64` set
- **THEN** the "Provision debug keystore" step decodes the base64 secret to
  `.android/debug.keystore`
- **AND** the subsequent `Validate flake`, `Verify devshell toolchain`, `Run
  JVM tests`, and `Assemble debug APK` steps observe a non-empty keystore file
  at that path

#### Scenario: Fork pull requests use an ephemeral debug keystore

- **WHEN** the CI workflow runs on a `pull_request` whose head repository
  differs from `github.repository` and the secret is unavailable
- **THEN** the "Provision debug keystore" step generates a non-empty JKS
  keystore with the Android debug alias and credentials
- **AND** the subsequent Gradle steps observe the generated file at that path

#### Scenario: Missing secret fails on trusted runs

- **WHEN** the `ANDROID_DEBUG_KEYSTORE_BASE64` secret is unset, empty, or
  decodes to an empty byte sequence outside a fork `pull_request`
- **THEN** the "Provision debug keystore" step fails with a non-zero exit code
  via `test -s .android/debug.keystore`
- **AND** no gradle task is invoked

#### Scenario: Existing debug signing config in build.gradle.kts is unchanged

- **WHEN** the change lands on `main`
- **THEN** `app/build.gradle.kts` retains its existing `signingConfigs.getByName("debug") { storeFile = rootProject.file(".android/debug.keystore"); ... }` block unmodified
- **AND** no developer-machine behavior changes for developers who already have `.android/debug.keystore` populated locally

### Requirement: The debug keystore SHALL NOT be committed to git

The keystore at `.android/debug.keystore` SHALL remain excluded from version
control via the existing `.android/` rule in `.gitignore`. Trusted CI runs
transport the keystore through the GitHub Actions secret. Fork pull requests
generate it at runtime. Neither path SHALL commit the keystore to git.

#### Scenario: Keystore is not tracked by git

- **WHEN** any commit is made after the change lands
- **THEN** `git check-ignore .android/debug.keystore` reports a match against `.gitignore`
- **AND** `git ls-files .android/` returns no entries

### Requirement: Previously-failing PRs SHALL pass CI after the change lands on main

The four PRs that fail `:app:validateSigningDebug` on the current `main` (#6, #7, #8, #9) and the in-progress PR #10 SHALL pass the `Build & Test (Nix)` check on their next CI run after the change merges to `main`, without requiring their authors to rebase, modify code, or re-trigger manually.

#### Scenario: Open PRs turn green without author action

- **WHEN** the CI workflow change is merged to `main`
- **AND** any of PRs #6–#10 re-runs CI (automatically via GitHub's branch-target-update trigger or via manual re-trigger)
- **THEN** the `Build & Test (Nix)` check reports success
- **AND** the `Assemble debug APK` and `Upload debug APK` steps complete without a keystore-not-found error
