# Changelog

All notable changes to Talkcan are documented in this file.

## [Unreleased]

### Changed

- Rebuilt the Android interface around Talkcan's warm-light can-and-string
  identity, with clearer channel, connection, setup, package, profile, and
  diagnostic workflows.
- Added branded launcher, notification, and Android Auto artwork; accessible
  state badges; edge-to-edge inset handling; and contrast-safe semantic colors.

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
