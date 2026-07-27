# AGENTS.md

## Development Environment

Work inside the repository devshell and run development tools directly.

If a required command is unavailable, run it ephemerally with Nix:

```sh
nix run nixpkgs#<package> -- <arguments>
```

Do not install Android, Gradle, Kotlin, Java, or build tooling globally. Do not use `brew`, `apt`, SDKMAN, or manually downloaded SDKs for this repository.

## Flake Facilities

The default devshell provides:

- JDK 17
- Gradle
- Kotlin compiler
- Kotlin language server (`kotlin-language-server`)
- Android Kotlin JDWP debug bridge (`talkcan-android-debug-adapter`), provisioned by
  `nix develop`; do not install it globally
- Android SDK platforms API 31 and API 35
- Android build tools 35.0.0
- Android platform tools

The shell exports:

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `JAVA_HOME`
- `GRADLE_OPTS` with the Nix-provided `aapt2` override

Gradle state is scoped to the repository by `GRADLE_USER_HOME=$PWD/.gradle`.

## Nix And Git Source Visibility

This repository is a Git worktree. Nix evaluates the flake from the Git source, so files required by flake evaluation must be tracked or staged before running flake commands.

If a new file is needed by `flake.nix`, stage it before evaluation:

```sh
git add <file>
nix flake check --no-write-lock-file
```

Do not stage unrelated user changes. Only stage files required for Nix to evaluate or files the user explicitly asks to stage.

## Standard Commands

Validate the flake:

```sh
nix flake check --no-write-lock-file
```

Verify the Gradle toolchain:

```sh
gradle --version
```

Run Gradle project commands:

```sh
gradle build
gradle test
```

If the project later adds a Gradle wrapper, prefer it:

```sh
./gradlew build
./gradlew test
```

## Releases

Releases are cut by pushing an annotated, GPG-signed `v*` tag. The
`Release` GitHub Actions workflow verifies the tag signature, builds a
release-signed APK from keystores provisioned via repository secrets,
verifies the APK signature, and publishes the artifact to GitHub
Releases. See `RELEASE_SIGNING.md` for the full operator checklist
(keystore generation, backups, rotation, GPG tag-signing setup).

The release signing config reads its keystore path and passwords from
environment variables (`ANDROID_RELEASE_KEYSTORE_PATH`,
`ANDROID_RELEASE_KEYSTORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`);
when these are unset, `gradle assembleRelease` produces an unsigned APK
and does not fall back to debug signing.

## Android Device Testing

Use a physical Android 12+ device with USB debugging enabled. The target hardware is `B02PTT-FF01`.

Check that ADB can see the device:

```sh
adb devices
```

The expected state is `<serial>    device`. If the state is `unauthorized`, accept the USB debugging prompt on the phone.

Install the debug build:

```sh
gradle installDebug
```

Launch the app:

```sh
adb shell am start -n io.talkcan/.MainActivity
```

### OMP Kotlin Debugging

Start OMP through `nix develop` at the repository root. Install and launch the debug app with the
commands above, then call the OMP Debug tool with `action: "attach"`,
`adapter: "talkcan-android-kotlin"`, `port: 37099`, and `cwd` set to the repository root.

The wrapper finds `io.talkcan`, forwards `tcp:37099` to its JDWP endpoint, and
removes the forward when the debug session terminates.

An unattached or unauthorized device, an absent app process, or a non-debuggable/release app
causes the wrapper or adapter to fail instead of attaching elsewhere. Restore the required state
with `adb devices`, `gradle installDebug`, and
`adb shell am start -n io.talkcan/.MainActivity` through `nix develop` as documented
above. The bridge covers Kotlin, Java, and ART frames; ARM64 JNI frames require a native
debugger.

In-app test flow:

- Tap `Grant permissions`.
- Enable Bluetooth if needed.
- Put `B02PTT-FF01` in pairing mode.
- Tap `Scan for device`.
- Tap `Pair device` if found unpaired.
- Tap `Connect serial`.
- If `Headset audio capability` stays unavailable, open Android Bluetooth settings, connect the headset/calls profile, then retry readiness checks.

Manual acceptance checks:

- Press/release PTT; the monitor must show `pressed` and `released`.
- Press Group; hardware mode must change to `Control`.
- Press PTT while in Control; hardware mode must return to `Active`.
- In Control mode, press Volume Up and Volume Down; each row must show `clicked` and return to `idle` after 300 ms.
- Enable echo, hold PTT, speak, release; beep, recording, and playback must route through the headset, not the phone.
- Switch to another app while serial is connected; echo must continue working and Android must show the `Talkcan connected` foreground-service notification.
- Tap `Disconnect serial`; the foreground-service notification must be removed.

## Android Project Constraints

Use the SDK supplied by the flake. Do not create or rely on `local.properties` for SDK discovery unless explicitly required by Android tooling; it is ignored by Git.

Keep the Android target aligned with the scaffold specification:

- Minimum SDK: API 31
- Target SDK: current stable Android SDK available in the flake, currently API 35
- Language: Kotlin
- Hidden Android APIs: not allowed

## Generated Files

Do not commit generated local state:

- `.gradle/`
- `.kotlin/`
- `build/`
- `local.properties`

These paths are ignored by `.gitignore`.

## Structural Code Changes

For deletions, renames, and wide refactors, spawn the `refactorer` agent
(`.omp/agents/refactorer.md`) via the `task` tool instead of editing inline.
It owns the inventory, rename, structural-edit, and verification methodology.
Not for feature work or single-file bug fixes.

When orchestrating a wide change:

- Work the OpenSpec `tasks.md` in listed order, one `refactorer` worker per
  task; run workers in parallel only when their file clusters are disjoint.
- Serialize edits to shared wiring files (`PttForegroundService`,
  `ServiceChannelCapabilityHost`, `ChannelImplementationProvider`,
  `ChannelCapabilityContracts`) — never run parallel workers that touch them.
- Use `scout` subagents for read-only callsite mapping.
