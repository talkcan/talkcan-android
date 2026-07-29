# Release Signing

Operator-facing documentation for the Talkcan release signing pipeline.
Defines where keys live, how to back them up, what happens if they are
lost, how to rotate them, and how to cut a release.

The release workflow itself lives at `.github/workflows/release.yml` and is
triggered by a GPG-signed `v*` tag. The contract it enforces is described
in `openspec/specs/release-signing-pipeline/spec.md` (after the
`android-release-signing-pipeline` change archives).

## Keystore location

The release keystore is **not** in the repository. It lives in three
places:

1. Two physically separate encrypted offline backups held by the operator
   (locations are operator-private; record them in your own notes, not
   here).
2. Base64-encoded inside the GitHub Actions repository secret
   `ANDROID_RELEASE_KEYSTORE_BASE64`.
3. Ephemeral decode at `${{ github.workspace }}/release.keystore` on the
   CI runner, scoped to a single workflow run and destroyed with the
   runner.

The keystore never appears in git history and is never committed, even
base64-encoded. The `.gitignore` excludes `release.keystore` and
`*.keystore` defensively.

## Key parameters

| Parameter | Value |
|---|---|
| Algorithm | RSA-4096 |
| Validity | 9125 days (~25 years) from generation |
| Key alias | `talkcan-release` |
| Keystore password | operator-chosen, stored in `ANDROID_RELEASE_KEYSTORE_PASSWORD` |
| Key password | operator-chosen, stored in `ANDROID_RELEASE_KEY_PASSWORD` |
| Signing schemes | APK Signature Scheme v2, v3 (enforced by `apksigner`) |

RSA-4096 / 25-year validity matches Google Play Store's release-key
requirements, future-proofing against later Play enrollment without
forcing a key rotation.

## Backup requirement (mandatory before first release)

Before uploading any release secret to GitHub, the keystore **must** be
copied to two physically separate encrypted offline locations. Examples
that satisfy "physically separate":

- An encrypted USB drive stored in a safe + an encrypted attachment in a
  password manager stored on a different machine.
- Two USB drives in two different physical locations (home safe + bank
  deposit box).

Examples that do **not** satisfy the requirement:

- One USB drive + one copy on the same laptop that generated the key.
- Two cloud-storage uploads to the same provider.
- A single password-manager entry, even if the password manager
  replicates across devices.

Record the backup locations in operator-private notes (not in the repo,
not in the password-manager entry's metadata that travels with the
attachment).

## Loss incident procedure

If the release keystore is lost (both backups unreadable, or the GitHub
secret is rotated by an attacker and the originals are gone):

Under the current channel mix (GitHub Releases only, no Google Play
enrollment), **there is no upgrade path**. Existing installs signed with
the lost key cannot be upgraded by APKs signed with a new key. Users must
uninstall before installing any future release.

Mitigations, in order of preference:

1. **Recover from backup.** This is why backups are mandatory. Restore
   the keystore, re-upload `ANDROID_RELEASE_KEYSTORE_BASE64`, ship the
   next release normally.
2. **Accept the signature reset.** Generate a new keystore, document the
   break in the next release notes, instruct users to uninstall first.
   This burns the package's reputation on every channel that was
   tracking the old signature.
3. **Enroll Play App Signing before this ever becomes urgent.** Out of
   scope for the current pipeline, but Play's escrow model would let
   Google hold a copy of the app-signing key and rotate via their
   infrastructure. If the project's threat model grows to require this,
   Play enrollment becomes the mitigation of choice.

## Rotation procedure

To rotate the release signing key (e.g., after a suspected compromise,
or proactively after a long interval):

1. Generate a new keystore with the same parameters as the original
   (RSA-4096, 9125-day validity, alias `talkcan-release`).
2. Back up to two physically separate encrypted offline locations.
3. Update the three GitHub secrets
   (`ANDROID_RELEASE_KEYSTORE_BASE64`,
   `ANDROID_RELEASE_KEYSTORE_PASSWORD`,
   `ANDROID_RELEASE_KEY_PASSWORD`).
4. Bump `versionCode`, ship the next release normally.
5. Document the rotation in the release notes — users on the old
   signature will need to uninstall before upgrading.

Rotation does **not** require any code change beyond `versionCode`.
The gradle config reads from environment variables; the new keystore is
picked up automatically once the secrets are updated.

## GPG tag-signing setup

Release tags must be GPG-signed. The release workflow imports the
signer's public key from `.github/release-signing-pubkey.asc` and
verifies the tag signature before any build runs.

Local setup (one-time, per machine that cuts releases):

```sh
git config user.signingKey <your-yubikey-backed-key-id>
git config tag.gpgSign true
git config commit.gpgSign true   # optional but recommended
```

To rotate the GPG signing key (e.g., new Yubikey, new subkey):

1. Export the new public key:
   `gpg --armor --export <new-signing-subkey-id> > .github/release-signing-pubkey.asc`
2. Commit the updated `.github/release-signing-pubkey.asc`.
3. Optionally re-sign historical tags if verification of older releases
   must still work under the new key.
4. Old signed tags remain valid against historical releases; only
   future tags require the new key.

## Operator checklist: cutting a release

1. Confirm `ANDROID_DEBUG_KEYSTORE_BASE64`,
   `ANDROID_RELEASE_KEYSTORE_BASE64`,
   `ANDROID_RELEASE_KEYSTORE_PASSWORD`, and
   `ANDROID_RELEASE_KEY_PASSWORD` are all set in repository secrets
   (`gh secret list | grep ANDROID_`).
2. Confirm `.github/release-signing-pubkey.asc` matches the GPG key
   currently configured for tag signing.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. Commit the version bump on `main` with a message like
   `release: bump version to <versionName> for signed release`.
5. Push to `main`. Wait for the `CI` workflow to report green
   (`gh run watch`).
6. Create the signed tag (Yubikey touch required):
   `git tag -s v<versionName> -m "v<versionName>"`
7. Push the tag: `git push origin v<versionName>`.
8. Watch the `Release` workflow:
   `gh run watch --workflow=release.yml`.
   Every step through "Publish GitHub Release" must be green.
9. Verify the GitHub Release exists:
   `gh release view v<versionName>` (confirm the APK asset is attached).
10. Verify the APK signature locally:
    ```sh
    gh release download v<versionName> --pattern '*.apk' --dir /tmp/release-check
    apksigner verify --verbose --print-certs --min-sdk-version 24 \
      /tmp/release-check/*.apk
    ```
    The API 24 verification floor makes `apksigner` evaluate both embedded
    v2 and v3 signing blocks. It does not change the APK's API 31 minimum.
11. Smoke-test on `B02PTT-FF01` per the `AGENTS.md` manual acceptance
    flow: PTT press/release, Group → Control mode, Volume Up/Down in
    Control mode, echo with headset routing, foreground-service
    notification on app switch, disconnect cleanup.
12. Edit the auto-generated release notes to include any
    signature-mismatch note for users upgrading from a debug build
    (uninstall required).

## What this pipeline does **not** cover

- **F-Droid submission.** Path C (`Binaries` anti-feature for the ONNX
  model blobs) is accepted in principle but deferred to a separate
  change. When ready, the F-Droid metadata file
  (`io.talkcan.yml`) will reference the GitHub Releases
  APK URL via the `Binaries:` field.
- **Google Play Store / Play App Signing.** No enrollment. Adding Play
  later requires a separate workflow with the Play upload key enrolled
  via Google's Play Console, orthogonal to this pipeline.
- **R8 / ProGuard / resource shrinking.** The release build type has
  `isMinifyEnabled = false` and `isShrinkResources = false`. Enabling
  minification requires hardware-verified ProGuard rules and lives in
  a future change.
- **Detached GPG signatures on APK artifacts** (`.asc` files alongside
  the APK). The APK v2/v3 signature is authoritative; a detached
  signature would be redundant for Android and ignored by F-Droid.
- **Self-hosted runner with Yubikey PKCS11 for HSM-backed APK signing.**
  The release key lives in a GitHub secret, which is acceptable for
  the current single-developer threat model. The HSM-backed path is
  documented in the design (`openspec/changes/android-release-signing-pipeline/design.md`,
  Decision 4) as a future option if the threat model demands it.
- **Sigstore / cosign keyless attestation.** Future direction; not
  implemented.
