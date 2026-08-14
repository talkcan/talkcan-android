# Changelog

All notable changes to Talkcan are documented in this file.

## [Unreleased]

### Added

- Added host-enforced recording limits from 1 through 10 minutes for external
  Lua input channels.
- Added semantic warning tones that play through the active capture route
  before a recording reaches its limit.

### Changed

- Added the approved industrial visual system to the main dashboard. It uses
  graphite surfaces, aluminum copy, amber voice routes, and cyan status.
- Added reusable instrument panels, route-status badges, technical typography,
  calibration grid lines, and contrast-safe system bars.
- Added first-class Radio and Settings navigation, relocated app-wide
  configuration, selection-only channel and audio-device controls, and a
  fixed accessible phone PTT dock with strict press-and-hold behavior.
- Compacted Radio and Settings, removed the duplicate current-channel summary,
  strengthened selection and readiness states, and made phone PTT a filled
  action with explicit destination and release guidance.
- Kept the audio-device selector fixed above phone PTT and replaced it in place
  with a talk-level meter using the same header and 96 dp card geometry.

### Fixed

- Refreshed built-in voice profile availability after model acquisition so
  the ten shipped voices cannot remain incorrectly marked as missing.
- Kept the phone PTT pointer session active until pointer-up so releasing the
  on-screen control always stops its recording session.

## [0.10.0] - 2026-07-29

### Added

- First Talkcan Android release, rebranded from the original Subspace
  codebase under the `io.talkcan` application namespace.
- Hardware push-to-talk input and Bluetooth headset, handset, and car audio
  routing on Android 12 and later.
- Sandboxed Lua channel runtime with typed audio, HTTP, secret, filesystem,
  keyboard, profile, resolver, and durable-work capabilities.
- GitHub channel discovery, package installation and updates, trust
  disclosure, and recommended Talkcan channels.
- On-device Parakeet speech-to-text and Supertonic text-to-speech model
  support.

### Security

- Release APKs use a dedicated RSA-4096 key and Android APK Signature
  Schemes v2 and v3.
- The release workflow requires a signed Git tag and verifies the APK
  signature before publication.
