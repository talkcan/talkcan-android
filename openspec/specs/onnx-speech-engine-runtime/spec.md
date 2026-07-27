## Purpose

Defines the runtime contract for on-device speech inference after the Kotlin/Java ONNX Runtime cutover: Parakeet v3 speech-to-text and Supertonic 3 text-to-speech execute exclusively through the `com.microsoft.onnxruntime:onnxruntime-android` AAR via typed Kotlin ONNX Runtime APIs, with no first-party native libraries, JNI bridges, or custom JSON codecs. The capability preserves existing model artifacts, transcription and synthesis semantics, asynchronous observable loading, deterministic native-resource ownership, serialized bounded local inference, and a proven-cutover gate before old engines are removed.
## Requirements
### Requirement: Speech models execute through the supported Android Java API
The system SHALL execute Parakeet v3 and Supertonic 3 with `com.microsoft.onnxruntime:onnxruntime-android` through Java/Kotlin ONNX Runtime APIs. The application SHALL NOT ship or invoke a first-party Parakeet or Supertonic native library, custom model JNI entry point, or JNI JSON outcome codec.

#### Scenario: Production APK model runtime is inspected
- **WHEN** the production APK and its runtime call paths are inspected after cutover
- **THEN** ONNX Runtime is supplied by the Android AAR
- **AND** `libtalkcan_parakeet.so` and `libtalkcan_supertonic.so` are absent
- **AND** no Kotlin production class declares or calls their former native methods

#### Scenario: Speech inference completes
- **WHEN** either speech engine executes a valid request
- **THEN** Kotlin passes typed tensors directly to ONNX Runtime and projects the result into the existing typed host outcome
- **AND** it does not serialize a transcript, error, or PCM sample array through a custom JNI JSON response

### Requirement: Existing speech model artifacts remain authoritative
The system SHALL retain the current Parakeet v3 and Supertonic 3 model families, manifest identities, SHA-256 hashes, download repositories, on-disk filenames, vocabularies, configuration files, voice styles, and language sets. The runtime replacement SHALL NOT require a model download or format migration when the existing verified model directories are present.

#### Scenario: Existing verified model directories are reused
- **WHEN** an application update starts with the current verified Parakeet and Supertonic model directories and matching completion markers
- **THEN** both Kotlin engines load those files without renaming, converting, or downloading replacement graphs

#### Scenario: Model manifest is compared across cutover
- **WHEN** the bundled `model-hashes.json` before and after the runtime replacement is compared
- **THEN** the Parakeet and Supertonic model identities, versions, repositories, file maps, and hashes are unchanged

### Requirement: Parakeet execution preserves transcription semantics
The Parakeet engine SHALL run the existing raw-waveform preprocessor, int8 encoder, and combined decoder/joint sessions and SHALL preserve the existing 16 kHz mono input contract, 250 ms leading silence, encoder-axis interpretation, recurrent decoder state, blank token, maximum ten emitted tokens per encoded step, maximum-logit greedy selection, vocabulary order, and whitespace decoding. It SHALL return only the decoded text through the existing `TranscriptionOutcome` contract.

#### Scenario: Deterministic recording is transcribed across cutover
- **WHEN** the previous and replacement engines receive the same normalized deterministic 16 kHz PCM fixture with the same verified model files
- **THEN** the replacement engine returns the same decoded transcript

#### Scenario: Empty recording is submitted
- **WHEN** the replacement transcriber receives an empty sample array
- **THEN** it returns the existing empty-input outcome without invoking an ONNX session

#### Scenario: Decoder emits multiple tokens at one time step
- **WHEN** the decoder/joint graph repeatedly selects nonblank tokens for one encoded time step
- **THEN** the engine advances recurrent state for each emitted token
- **AND** it advances to the next encoded time step after a blank token or the ten-token bound

### Requirement: Supertonic execution preserves synthesis semantics
The Supertonic engine SHALL run the existing duration predictor, text encoder, vector estimator, and vocoder sessions and SHALL preserve supported-language validation, Unicode preprocessing, voice-style tensors, duration speed adjustment, Gaussian latent sampling, masks, requested denoising steps, language-dependent text chunking, inter-chunk silence, and finite mono 44.1 kHz PCM output. It SHALL project results through the existing `SynthesisOutcome` contract.

#### Scenario: Valid synthesis request completes
- **WHEN** the engine receives nonblank supported text, a verified voice-style file, a supported language, a valid positive speed, and a valid total-step count
- **THEN** it returns nonempty finite mono 44.1 kHz PCM synthesized with those exact parameters

#### Scenario: Long text requires multiple chunks
- **WHEN** input text exceeds the existing language-dependent chunk limit
- **THEN** the engine synthesizes the ordered chunks independently
- **AND** it concatenates them with the existing silence duration and without boxed per-sample accumulation

#### Scenario: Empty text is submitted
- **WHEN** the engine receives blank text
- **THEN** it returns the existing empty-text outcome without invoking an ONNX session or reading a voice-style file

#### Scenario: Unsupported language is submitted
- **WHEN** the engine receives a language outside the existing Supertonic language set
- **THEN** it returns the existing normalized synthesis failure
- **AND** it produces no PCM output

### Requirement: Model loading is asynchronous and observable
Each speech engine SHALL start complete model, vocabulary, configuration, and session loading off the Android main thread and SHALL expose the existing `Idle`, `Loading`, `Ready`, or `Failed` readiness projection and bounded load diagnostic. A successfully loaded engine SHALL reuse its sessions across subsequent requests without reloading model files.

#### Scenario: Engine construction starts loading
- **WHEN** bootstrap constructs a speech engine with a verified model directory
- **THEN** construction starts background loading and returns without waiting for all ONNX sessions to load
- **AND** readiness reports `Loading` until the complete session aggregate is atomically published or loading fails

#### Scenario: Partial session construction fails
- **WHEN** one required model, vocabulary, configuration, or session fails after earlier sessions were created
- **THEN** readiness reports `Failed` with a bounded diagnostic
- **AND** every partially created session and value is closed
- **AND** no incomplete aggregate is published as ready

#### Scenario: Subsequent request uses ready engine
- **WHEN** a second request arrives after the engine has reached `Ready`
- **THEN** it reuses the existing session aggregate without reading or recreating the model sessions

### Requirement: Speech engines have deterministic native-resource ownership
The process-scoped ONNX environment SHALL be shared through an injectable Kotlin owner, while each speech engine SHALL exclusively own and idempotently close its sessions and per-operation ONNX values. Service shutdown, bootstrap retry, construction failure, and load/close races SHALL leave no live superseded model generation and SHALL NOT close the environment while another engine can use it.

#### Scenario: Service generation shuts down after engines are ready
- **WHEN** structured service shutdown releases a ready STT or TTS engine
- **THEN** model pollers and controller work are stopped
- **AND** every engine-owned ONNX session is closed exactly once
- **AND** later calls cannot enter inference

#### Scenario: Shutdown races background loading
- **WHEN** an engine is closed before its background loader can publish a complete session aggregate
- **THEN** the loader closes every locally constructed session instead of publishing it
- **AND** no late readiness or inference effect is emitted

#### Scenario: Service restarts in one process
- **WHEN** one service generation shuts down and a replacement generation loads both models
- **THEN** only the replacement generation retains live model sessions after the prior shutdown completes
- **AND** process memory does not retain an additional complete superseded generation

#### Scenario: Temporary ONNX values leave scope
- **WHEN** any inference step succeeds, fails, or is cancelled between ONNX calls
- **THEN** every temporary tensor and session result owned by that step is closed exactly once

### Requirement: Inference remains serialized, bounded, and local
Each engine SHALL serialize its own inference requests, SHALL execute blocking ONNX work off the Android main thread, and SHALL wait no longer than the existing 120-second bound when a request races model loading. Captured audio, text, transcripts, model inputs, and synthesized PCM SHALL remain on-device and SHALL NOT be persisted by the engines.

#### Scenario: Two requests target one engine
- **WHEN** two admitted requests reach the same engine concurrently
- **THEN** their ONNX executions do not overlap
- **AND** each receives exactly one terminal typed outcome

#### Scenario: STT and TTS are independently admissible
- **WHEN** host policy admits one STT request and one TTS request concurrently
- **THEN** the separate engine locks do not serialize the two different engines against each other

#### Scenario: Request races prolonged loading
- **WHEN** an off-main-thread request waits for loading and readiness is not reached within 120 seconds
- **THEN** the request returns the existing model-not-ready outcome
- **AND** it does not wait indefinitely or block the Android main thread

#### Scenario: Engine processes sensitive content
- **WHEN** an engine receives captured speech or synthesis text
- **THEN** it performs no network request and persists no audio, text, transcript, tensor, or PCM artifact

### Requirement: Runtime cutover is proven before old engines are removed
The change SHALL establish pure Kotlin behavioral tests and real-model Android instrumentation before selecting the Kotlin engines as the only production path. Physical-device acceptance on `B02PTT-FF01` SHALL exercise model loading, transcription, synthesis, playback, service restart, latency, and memory, and both model operations SHALL complete within their existing host deadlines.

#### Scenario: Kotlin engines become production engines
- **WHEN** pure helper tests, real-model instrumentation, deterministic Parakeet transcript comparison, Supertonic PCM validation, audible playback, and restart resource checks pass
- **THEN** production factories select only the Kotlin engines
- **AND** the Rust model crates, JNI bridges, native build tasks, and candidate-only comparison scaffolding are removed

#### Scenario: Parakeet misses its existing deadline
- **WHEN** target-device Java/Kotlin decoder execution cannot complete within the existing transcription deadline after avoidable allocation and copy costs are removed
- **THEN** the cutover is not accepted
- **AND** the old production engine is not removed as part of a partial migration
