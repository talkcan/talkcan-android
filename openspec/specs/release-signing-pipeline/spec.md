# release-signing-pipeline Specification

## Purpose

Define the tag-triggered release workflow and the environment-driven release
signing configuration that produces a release-signed, signature-verified APK
published to GitHub Releases, with operator procedures documented in
`RELEASE_SIGNING.md`.

## Requirements

### Requirement: The release signing config SHALL be environment-driven with no debug fallback

`app/build.gradle.kts` SHALL declare a `release` signing config that reads `storeFile`, `storePassword`, and `keyPassword` from environment variables `ANDROID_RELEASE_KEYSTORE_PATH`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, and `ANDROID_RELEASE_KEY_PASSWORD` respectively. The `keyAlias` SHALL be the literal `talkcan-release`. When `ANDROID_RELEASE_KEYSTORE_PATH` is unset or points at a non-existent file, `gradle assembleRelease` SHALL produce an unsigned APK (`app-release-unsigned.apk`); it SHALL NOT silently fall back to debug signing.

#### Scenario: Release signing succeeds when environment is provisioned

- **WHEN** `gradle assembleRelease` is invoked with all three environment variables set and `ANDROID_RELEASE_KEYSTORE_PATH` pointing at a valid keystore file
- **THEN** the build produces `app/build/outputs/apk/release/app-release.apk`
- **AND** `apksigner verify --verbose --min-sdk-version 24` reports successful verification under APK Signature Scheme v2 and v3

#### Scenario: Release build produces unsigned APK when environment is absent

- **WHEN** `gradle assembleRelease` is invoked without `ANDROID_RELEASE_KEYSTORE_PATH` set, or with a path that does not exist
- **THEN** the build produces `app/build/outputs/apk/release/app-release-unsigned.apk`
- **AND** no debug keystore is used to sign the release build
- **AND** the build does not fail at configuration time

### Requirement: The release build type SHALL disable minification and resource shrinking

The `release` build type in `app/build.gradle.kts` SHALL set `isMinifyEnabled = false` and `isShrinkResources = false`. The release build type SHALL reference the `release` signing config. These settings ensure the release APK is behaviorally identical to the debug APK for the first release; minification can be added in a future change with hardware-verified ProGuard rules.

#### Scenario: Release APK is behaviorally equivalent to debug APK

- **WHEN** the release APK is installed on `B02PTT-FF01` hardware
- **AND** the operator runs the manual acceptance flow defined in `AGENTS.md` (PTT press/release, Group mode switch, Volume controls in Control mode, echo with headset routing, foreground service notification on app switch, disconnect cleanup)
- **THEN** all checks pass identically to the debug build
- **AND** no release-only crashes, missing-class errors, or stripped-symbol failures occur

### Requirement: The release workflow SHALL be triggered only by signed version tags

The `.github/workflows/release.yml` workflow SHALL trigger on `push` to tags matching `v*` and on `workflow_dispatch`. It SHALL NOT trigger on pushes to `main` or on pull requests. Before any build step, the workflow SHALL import the release tag signer's public key from `.github/release-signing-pubkey.asc` and SHALL verify the triggering tag's GPG signature; the workflow SHALL fail if the signature is missing, invalid, or untrusted.

#### Scenario: Signed tag triggers a successful release

- **WHEN** the operator pushes an annotated GPG-signed tag matching `v*` (e.g., `git tag -s v0.2.0 && git push origin v0.2.0`)
- **THEN** the release workflow runs
- **AND** the "Verify release tag signature" step observes "Good signature from" in `git tag -v` output
- **AND** the workflow proceeds to build and publish

#### Scenario: Unsigned tag fails the workflow before any build

- **WHEN** the operator pushes an unsigned tag matching `v*` (e.g., `git tag v0.2.0 && git push origin v0.2.0`)
- **THEN** the release workflow runs
- **AND** the "Verify release tag signature" step fails because `git tag -v` output does not contain "Good signature from"
- **AND** no gradle task, keystore provisioning, or GitHub Release creation occurs

#### Scenario: Push to main does not trigger a release

- **WHEN** any commit is pushed to `main`
- **THEN** the release workflow does not run
- **AND** no GitHub Release is created

### Requirement: The release workflow SHALL provision both debug and release keystores from secrets

The release workflow SHALL decode `ANDROID_DEBUG_KEYSTORE_BASE64` to `.android/debug.keystore` and `ANDROID_RELEASE_KEYSTORE_BASE64` to a path under `github.workspace` (e.g., `${{ github.workspace }}/release.keystore`) before invoking `gradle assembleRelease`. The workflow SHALL export `ANDROID_RELEASE_KEYSTORE_PATH`, `ANDROID_RELEASE_KEYSTORE_PASSWORD` (from secret), and `ANDROID_RELEASE_KEY_PASSWORD` (from secret) into the gradle task's environment. Both provisioning steps SHALL fail the workflow if the decoded file is empty.

#### Scenario: Both keystores provisioned and gradle receives signing environment

- **WHEN** the "Provision keystores" step runs in the release workflow
- **THEN** `.android/debug.keystore` and `${{ github.workspace }}/release.keystore` both exist and are non-empty
- **AND** the subsequent `gradle assembleRelease` step observes all three signing environment variables

#### Scenario: Missing release secret fails before gradle runs

- **WHEN** `ANDROID_RELEASE_KEYSTORE_BASE64` is unset or decodes to an empty file
- **THEN** the "Provision keystores" step fails via `test -s release.keystore`
- **AND** no gradle task is invoked

### Requirement: The release workflow SHALL verify the APK signature before publishing

After `gradle assembleRelease`, the workflow SHALL run `apksigner verify --verbose --print-certs --min-sdk-version 24` against the produced APK. The API 24 verification floor SHALL force `apksigner` to evaluate both embedded v2 and v3 signing blocks without changing the APK's declared API 31 minimum. The workflow's inner shell SHALL use fail-fast semantics, and the workflow SHALL fail if either exact v2 or v3 success line is absent. Publishing SHALL NOT occur for an unverified or unsigned APK.

#### Scenario: Verified APK is published

- **WHEN** `apksigner verify --verbose --min-sdk-version 24` reports v2 and v3 scheme verification success
- **THEN** the workflow proceeds to the "Publish GitHub Release" step
- **AND** a GitHub Release is created with the APK as an asset

#### Scenario: Unverified APK blocks publishing

- **WHEN** `gradle assembleRelease` produced an unsigned APK (e.g., because signing env was incomplete despite keystore presence)
- **THEN** the "Verify APK signature" step fails
- **AND** no GitHub Release is created

### Requirement: The release workflow SHALL publish to GitHub Releases via the gh CLI

The workflow SHALL use `gh release create "${GITHUB_REF_NAME}" --title "${GITHUB_REF_NAME}" --generate-notes <apk-path>` to publish. The workflow SHALL NOT use a third-party GitHub Action for release creation. The `GITHUB_TOKEN` with `contents: write` permission SHALL authenticate the publication.

#### Scenario: Successful release publication

- **WHEN** all preceding steps succeed
- **THEN** `gh release create` runs against the triggering tag
- **AND** the GitHub Release titled `${GITHUB_REF_NAME}` appears in the repository's Releases page
- **AND** the release contains the signed APK as a downloadable asset
- **AND** release notes are auto-generated from commit history between the previous tag and this tag

### Requirement: The release tag signer's public key SHALL be committed to the repository

The ASCII-armored public key of the GPG key authorized to sign release tags SHALL be committed to the repository at `.github/release-signing-pubkey.asc`. This file is public (not a secret). The release workflow SHALL import this file via `gpg --import` before verifying the tag signature.

#### Scenario: Public key is available to CI without a secret

- **WHEN** the release workflow runs
- **THEN** `.github/release-signing-pubkey.asc` is present in the checked-out repository
- **AND** `gpg --import .github/release-signing-pubkey.asc` succeeds
- **AND** the subsequent `git tag -v` can verify tags signed by the corresponding private key

### Requirement: RELEASE_SIGNING.md SHALL document operator procedures

A top-level `RELEASE_SIGNING.md` file SHALL document: keystore location (the GitHub Actions secret, not a file in the repo), key parameters (RSA-4096, 25-year validity, alias `talkcan-release`), the two-physical-location backup requirement, the loss-incident procedure (no upgrade path under current channel mix without Play App Signing), the rotation procedure, the GPG tag-signing setup, and a release-cutting operator checklist.

#### Scenario: Operator can cut a release by following the documented checklist

- **WHEN** a new operator (or the existing operator after a long gap) follows the release-cutting checklist in `RELEASE_SIGNING.md`
- **THEN** the steps are sufficient to produce a published release without consulting external documentation
- **AND** every development-tool command in the checklist runs directly in the repository devshell; unavailable tools are invoked ephemerally with Nix rather than installed globally.

### Requirement: The release workflow SHALL NOT enable minification or resource shrinking

This requirement restates Decision 7 of the design at the spec level. The release build type SHALL have `isMinifyEnabled = false` and `isShrinkResources = false`. Future changes that enable minification SHALL do so in a separate change with hardware-verified ProGuard rules.

#### Scenario: First release APK is not minified

- **WHEN** the first tagged release (`v0.2.0`) is built by the release workflow
- **THEN** the resulting APK is not minified (R8 did not run)
- **AND** the APK is not resource-shrunk
- **AND** the APK's bytecode is equivalent to the debug build's bytecode modulo the signing block
