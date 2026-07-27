---
name: talkcan-publish-release
description: Publish a new Talkcan GitHub Release from a changelog-backed version bump. Use when cutting a release, pushing a signed v* tag, babysitting CI, updating release notes, or documenting release learnings.
license: MIT
compatibility: Requires gh CLI auth, git push access, Nix devshell, configured GPG tag signing key, and connected Yubikey when the signing subkey is hardware-backed.
metadata:
  author: talkcan
  version: "1.0"
---

# Publishing a Talkcan Release

Use this skill to cut a new GitHub Release for Talkcan.

Talkcan releases are tag-triggered. A GPG-signed annotated `v*` tag starts `.github/workflows/release.yml`; that workflow verifies the tag signature, builds a release-signed APK, verifies the APK with `apksigner`, uploads the APK artifact, and creates the GitHub Release.

## Preconditions

- Work from the repository root.
- Use the flake-provided toolchain; do not install Android, Gradle, Java, Kotlin, or SDK tooling globally.
- `gh auth status` must show a GitHub account with `repo` scope for `github.com`.
- `git config tag.gpgSign` should be `true`.
- `git config user.signingKey` must identify the key whose public key is committed at `.github/release-signing-pubkey.asc`.
- The Yubikey must be connected before `git tag -s` if the signing subkey is hardware-backed.
- Repository secrets must already contain:
  - `ANDROID_DEBUG_KEYSTORE_BASE64`
  - `ANDROID_RELEASE_KEYSTORE_BASE64`
  - `ANDROID_RELEASE_KEYSTORE_PASSWORD`
  - `ANDROID_RELEASE_KEY_PASSWORD`

## Release procedure

1. **Determine the next version**

   - Read existing `v*` tags: `git tag --list 'v*' --sort=-v:refname`.
   - For a new minor release from `v0.2.0`, use `v0.3.0`; increment `versionCode` monotonically.
   - Inspect merged commits/PRs since the last tag before writing notes. Include remote `main`; if push later rejects as non-fast-forward, fetch/rebase and re-check the changelog before tagging.

2. **Build or update `CHANGELOG.md`**

   - Use Keep a Changelog structure:
     - `## [Unreleased]`
     - `## [x.y.z] - YYYY-MM-DD`
     - `### Added`, `### Changed`, `### Fixed` as needed.
   - Include all user-visible merged work since the previous tag.
   - Include compare links at the bottom:
     - `[Unreleased]: https://github.com/talkcan/talkcan-android/compare/vx.y.z...HEAD`
     - `[x.y.z]: https://github.com/talkcan/talkcan-android/compare/vprevious...vx.y.z`
   - If remote `main` advanced while preparing the release, rebase the release commit onto the fetched main and update the changelog for that new work before tagging.

3. **Bump Android version fields**

   In `app/build.gradle.kts`:

   - Increment `defaultConfig.versionCode` by one.
   - Set `defaultConfig.versionName` to the release version without the leading `v`.

4. **Verify locally before pushing**

   Run:

   ```sh
   gradle test
   ```

   If a known flaky test fails, rerun the exact failing test once to distinguish flake from deterministic failure, then rerun full `gradle test`. Do not tag if full tests are still red.

   Observed during `v0.3.0`: `JournalWavWriterTest.finalizeDuringInFlightWriteSerializesAndDoesNotThrow` failed once in `:app:testReleaseUnitTest`, passed when rerun directly, and full `gradle test` then passed.

5. **Commit release prep**

   Commit only the changelog and version bump unless the user explicitly requested more release-prep changes:

   ```sh
   git add CHANGELOG.md app/build.gradle.kts
   git commit -m "release: prepare <version>"
   ```

6. **Push `main`**

   ```sh
   git push origin HEAD:main
   ```

   If SSH push fails but `gh` is authenticated, either push explicitly to HTTPS or configure the repo-local GitHub credential helper:

   ```sh
   git config credential.https://github.com.helper '!gh auth git-credential'
   git push https://github.com/talkcan/talkcan-android.git HEAD:main
   ```

   If the push rejects as non-fast-forward:

   ```sh
   git fetch https://github.com/talkcan/talkcan-android.git main
   git rebase FETCH_HEAD
   ```

   Then re-check the changelog/version bump, amend if needed, rerun tests, and push again.

7. **Create and verify the signed tag**

   ```sh
   git tag -s v<version> -m "v<version>"
   git tag -v v<version>
   ```

   The verification output must include `Good signature` and show the tag object points at the pushed release-prep commit.

8. **Push the tag**

   ```sh
   git push origin v<version>
   ```

   If needed, use the authenticated HTTPS remote form:

   ```sh
   git push https://github.com/talkcan/talkcan-android.git v<version>
   ```

9. **Babysit CI through publication**

   Watch both workflows:

   ```sh
   gh run list --workflow=ci.yml --limit 5
   gh run list --workflow=release.yml --limit 5
   gh run watch <ci-run-id>
   gh run watch <release-run-id>
   ```

   Required `CI` job steps:

   - Validate flake
   - Verify devshell toolchain
   - Provision debug keystore
   - Run JVM tests
   - Assemble debug APK
   - Upload debug APK

   Required `Release` job steps:

   - Verify release tag signature
   - Validate flake
   - Verify devshell toolchain
   - Provision keystores
   - Build release APK
   - Verify APK signature
   - Upload release APK
   - Publish GitHub Release

   Do not proceed to release-note editing until the Release workflow is green and `Publish GitHub Release` completed.

10. **Verify the published release**

    ```sh
    gh release view v<version>
    gh release view v<version> --json assets,tagName,url
    ```

    Confirm:

    - `draft: false`
    - `prerelease: false`
    - asset `app-release.apk` exists
    - asset content type is `application/vnd.android.package-archive`
    - the asset has a SHA-256 digest in GitHub metadata

11. **Replace auto-generated release notes with changelog text**

    Extract the just-published section from `CHANGELOG.md`, convert the heading to `# v<version>`, and append the full compare URL.

    Example body shape:

    ```md
    # v<version>

    ### Added
    ...

    ### Changed
    ...

    ### Fixed
    ...

    **APK**: `app-release.apk`

    **Full changelog**: https://github.com/talkcan/talkcan-android/compare/v<previous>...v<version>
    ```

    Write it to a temp file and edit the release:

    ```sh
    gh release edit v<version> --notes-file /tmp/talkcan-v<version>-release-notes.md
    gh release view v<version>
    ```

    Verify the visible release description matches the changelog section, not the generated PR list.

## Lessons from the `v0.3.0` release

- Remote `main` may advance while preparing a release. A rejected push is not a blocker: fetch `main`, rebase the release commit onto `FETCH_HEAD`, update `CHANGELOG.md` for the newly merged work, rerun tests, then push.
- This repo may use OMP project skills under `.omp/skills/<name>/SKILL.md`; write release workflow skills there, not only under legacy Claude user skill directories.
- If global Git config is read-only, configure the GitHub credential helper in the repository-local config instead of running `gh auth setup-git` globally.
- The release workflow can be almost complete by the time the main CI watcher finishes; still watch both runs and record both results.
- GitHub's generated release notes can duplicate PR entries. Treat generated notes as temporary; replace them with the curated changelog after publication.
- `gh release view --json assets,tagName,url` exposes the uploaded APK digest and content type without downloading the 800+ MB APK.

## Completion evidence to report

Report these concrete facts after release:

- release version and tag
- release commit SHA
- local test command result
- local tag verification result
- pushed tag result
- CI run ID and result
- Release run ID and result
- release URL
- APK asset name and digest
- release notes update verification
