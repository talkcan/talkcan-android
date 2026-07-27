## Purpose

Removed. PCM-to-OGG/Vorbis encoding was the final first-party Rust/JNI codec. All recording export uses `wav-pcm-s16le`; Journal voice entries retain `recording.wav` as their final media artifact.

## Requirements

No active requirements. Both former requirements (PCM to OGG/Vorbis encoding, encoding failure fallback) were removed by the `remove-rust-ogg-backend` change. Successful publication of the complete WAV document is voice-artifact completion; existing storage and typed-failure behavior governs WAV publication.
