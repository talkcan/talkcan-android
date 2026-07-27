# Native Navigation TTS

## Purpose

Define the on-device Android `TextToSpeech` engine bootstrap gate that synthesizes navigation announcements on demand at request time from the current channel catalogue, selects and probes a deterministic installed offline English voice, produces transient 16 kHz mono PCM16, enforces latest-wins synthesis and playback ownership, and recovers bounded runtime renderer failures without degraded fallback.

## Requirements

### Requirement: Android TTS engine initialization and manifest visibility
The system SHALL construct an Android `TextToSpeech` instance bound to the installed default engine during bootstrap prerequisite checking, before core native STT (Parakeet) and native TTS (Supertonic) initialization. The system SHALL declare an `<intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>` query in the application manifest so that engine visibility and service binding are available on API 31 and above. The system SHALL wait for `OnInitListener.onInit` to fire with `TextToSpeech.SUCCESS` before issuing any voice query, voice selection, or synthesis call. The system SHALL treat `TextToSpeech.ERROR` or an init timeout as a setup-phase engine init failure. The system SHALL NOT call `speak()` or use any engine-owned audible playback path at any point in the renderer lifecycle. Parakeet, Supertonic, and the proven Android TTS voice all remain hard bootstrap readiness gates.

#### Scenario: Successful engine initialization during prerequisite checking
- **WHEN** bootstrap prerequisite checking constructs an Android `TextToSpeech` instance before core native STT and Supertonic initialization
- **AND** `onInit` fires with `SUCCESS` within the configured init timeout
- **THEN** the renderer SHALL proceed to voice discovery
- **AND** it SHALL NOT issue any voice or synthesis call before `onInit(SUCCESS)` is received

#### Scenario: Engine init returns error
- **WHEN** `onInit` fires with `ERROR` or the init listener reports a negative status
- **THEN** the system SHALL classify the failure as an engine init failure
- **AND** bootstrap SHALL remain in `NeedsSetup` with an Android TTS settings or engine install action exposed to the user
- **AND** no voice query or synthesis call SHALL be attempted
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: Engine init times out
- **WHEN** the configured init timeout elapses without `onInit` firing
- **THEN** the system SHALL classify the failure as an engine init timeout
- **AND** bootstrap SHALL remain in `NeedsSetup` with a user-resolvable action
- **AND** the partially constructed `TextToSpeech` instance SHALL be shut down and released
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: No TTS engine installed
- **WHEN** the system queries installed engines via `getEngines()` and the list is empty or the default engine package is absent
- **THEN** the system SHALL classify the failure as engine unavailable
- **AND** bootstrap SHALL remain in `NeedsSetup` with an engine install action
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: Manifest query enables engine visibility
- **WHEN** the application is installed on an API 31+ device and the manifest declares the `android.intent.action.TTS_SERVICE` intent query
- **THEN** the system SHALL be able to bind to the installed TTS engine service
- **AND** `getEngines()` SHALL return the installed engine list without a security or visibility exception

#### Scenario: Android TTS gate does not bypass STT and Supertonic gates
- **WHEN** the Android TTS voice probe has completed successfully and core initialization begins
- **THEN** the system SHALL still require Parakeet to report `Ready` and Supertonic to report `Ready` before bootstrap enters the dashboard
- **AND** a failure in either native engine SHALL prevent dashboard entry regardless of the Android TTS gate state

#### Scenario: Cancellation during engine init
- **WHEN** bootstrap is canceled or retried while the `TextToSpeech` instance is awaiting `onInit`
- **THEN** the system SHALL call `shutdown()` on the instance and SHALL NOT process any late `onInit` callback
- **AND** no voice query or synthesis call SHALL be issued from the canceled initialization path

### Requirement: Deterministic installed offline English voice discovery with no network use
The system SHALL discover a deterministic installed offline English voice from `TextToSpeech.getVoices()` after successful engine initialization. A voice SHALL be a valid offline candidate only when all of the following hold: the voice locale language is English (`en`), `Voice.isNetworkConnectionRequired()` returns `false`, `Voice.getFeatures()` does not contain `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED`, and `isLanguageAvailable(voice.locale)` returns `LANG_AVAILABLE`, `LANG_COUNTRY_AVAILABLE`, or `LANG_COUNTRY_VAR_AVAILABLE`. The system SHALL sort valid candidates by `Voice.getLatency()` ascending, then `Voice.getQuality()` descending, then the locale tag ascending, then the voice name ascending, and SHALL select the first voice in that order to ensure deterministic selection across launches. The system SHALL NOT use a voice that requires network access, is marked not-installed, or whose language data is reported missing. The system SHALL NOT perform any network request during voice discovery.

#### Scenario: Single valid offline English voice discovered
- **WHEN** `getVoices()` returns a set containing exactly one English voice that is offline, installed, and language-available
- **THEN** the system SHALL select that voice via `setVoice`
- **AND** it SHALL proceed to the synthesis probe

#### Scenario: Multiple valid offline English voices sorted deterministically
- **WHEN** `getVoices()` returns multiple voices that all meet the offline, installed, and language-available criteria for English
- **THEN** the system SHALL sort them by latency ascending, then quality descending, then locale tag ascending, then voice name ascending
- **AND** it SHALL select the first voice in the sorted order
- **AND** the same voice SHALL be selected across launches given the same installed voice set

#### Scenario: Latency takes priority over quality in voice sort
- **WHEN** two valid offline English voices differ in latency and quality
- **THEN** the system SHALL select the lower-latency voice even if the higher-latency voice has higher quality

#### Scenario: Quality breaks ties when latency is equal
- **WHEN** two valid offline English voices have equal latency but different quality
- **THEN** the system SHALL select the higher-quality voice

#### Scenario: Locale tag breaks ties when latency and quality are equal
- **WHEN** two valid offline English voices have equal latency and equal quality but different locale tags
- **THEN** the system SHALL select the voice with the lexicographically smaller locale tag

#### Scenario: Network-dependent voice rejected
- **WHEN** a voice reports `isNetworkConnectionRequired() == true`
- **THEN** the system SHALL exclude that voice from the candidate set
- **AND** it SHALL NOT select or probe that voice

#### Scenario: Not-installed voice rejected
- **WHEN** a voice's `getFeatures()` contains `KEY_FEATURE_NOT_INSTALLED`
- **THEN** the system SHALL exclude that voice from the candidate set
- **AND** it SHALL NOT attempt to install or download voice data

#### Scenario: Missing language data rejected
- **WHEN** `isLanguageAvailable(voice.locale)` returns `LANG_MISSING_DATA` or `LANG_NOT_SUPPORTED` for an English voice
- **THEN** the system SHALL exclude that voice from the candidate set

#### Scenario: setVoice returns ERROR
- **WHEN** `setVoice` is called for the selected voice and returns `ERROR`
- **THEN** the system SHALL classify the failure as a voice selection failure
- **AND** it SHALL shut down the `TextToSpeech` instance
- **AND** bootstrap SHALL remain in `NeedsSetup` with an Android TTS settings action
- **AND** no synthesis probe SHALL be attempted
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: No valid offline English voice
- **WHEN** `getVoices()` returns no voice meeting all four validity criteria
- **THEN** the system SHALL classify the failure as voice missing
- **AND** bootstrap SHALL remain in `NeedsSetup` with an Android TTS settings action for the user to install an offline English voice
- **AND** no synthesis probe SHALL be attempted
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: getVoices returns null before init
- **WHEN** `getVoices()` is called before `onInit(SUCCESS)` and returns null or an empty set
- **THEN** the system SHALL treat this as voice missing rather than equating it to a null or absent engine list
- **AND** it SHALL NOT retry voice discovery outside of the bounded reinitialization path

#### Scenario: getVoices returns null despite engine being installed
- **WHEN** the engine is installed and `onInit(SUCCESS)` has fired but `getVoices()` returns null
- **THEN** the system SHALL classify this as voice missing, not as engine unavailable
- **AND** bootstrap SHALL remain in `NeedsSetup` with an Android TTS settings action

#### Scenario: Non-English voice excluded
- **WHEN** `getVoices()` contains installed offline voices whose locale language is not `en`
- **THEN** the system SHALL exclude all non-English voices from the candidate set

#### Scenario: Cancellation during voice discovery
- **WHEN** bootstrap is canceled or retried while voice discovery is in progress
- **THEN** the system SHALL stop further voice queries and SHALL shut down the `TextToSpeech` instance
- **AND** no voice selection or probe SHALL be committed from the canceled path

#### Scenario: Offline synthesis succeeds with data unavailable after voice proof
- **WHEN** Wi-Fi and mobile data are unavailable after the selected offline voice has been proven during bootstrap
- **AND** a runtime navigation announcement is synthesized using the selected voice that declared `isNetworkConnectionRequired() == false`
- **THEN** the synthesis SHALL succeed using the installed offline voice data
- **AND** the system SHALL set no network-specific TTS feature or parameter on the synthesis request
- **AND** the system SHALL NOT claim to police opaque engine network internals beyond selecting a voice that declares itself offline and verifying empirical offline synthesis success

### Requirement: Actual short synthesis probe and retained instance
The system SHALL perform an actual short silent synthesis probe after voice selection by synthesizing a short test phrase to a transient regular file via `synthesizeToFile` with a non-null utterance id. The system SHALL verify the probe output is non-empty, decodable, and reports a positive sample count before declaring the offline voice gate satisfied. The system SHALL NOT require the probe output to be any specific encoding, sample rate, or channel count; it SHALL accept any decodable non-empty audio and normalize it to 16 kHz mono PCM16 for validation. The system SHALL retain the successfully probed `TextToSpeech` instance with the selected voice for runtime synthesis. The system SHALL NOT declare the voice gate satisfied based on `isLanguageAvailable` or `getVoices` metadata alone; an actual synthesis producing non-empty decodable audio is a hard gate. The system SHALL enforce a bounded probe timeout and SHALL classify a probe timeout as a voice probe failure.

#### Scenario: Successful probe produces non-empty decodable audio
- **WHEN** the system synthesizes a short test phrase to a transient file after selecting a valid voice
- **AND** the synthesis callback fires `onDone` with a non-empty file that decodes as audio with a positive sample count
- **THEN** the system SHALL declare the offline voice gate satisfied
- **AND** it SHALL retain the `TextToSpeech` instance for runtime use

#### Scenario: Probe produces empty file
- **WHEN** the probe synthesis completes via `onDone` but the resulting file is empty, has zero samples, or is undecodable
- **THEN** the system SHALL classify the failure as a voice probe failure
- **AND** bootstrap SHALL remain in `NeedsSetup` with a user-resolvable action
- **AND** the retained instance SHALL be shut down

#### Scenario: Probe synthesis reports error
- **WHEN** the probe synthesis callback fires `onError` instead of `onDone`
- **THEN** the system SHALL classify the failure as a voice probe failure
- **AND** bootstrap SHALL remain in `NeedsSetup`

#### Scenario: Probe times out
- **WHEN** the configured probe timeout elapses without `onDone` or `onError` firing for the probe utterance
- **THEN** the system SHALL classify the failure as a voice probe timeout
- **AND** it SHALL call `stop()` on the `TextToSpeech` instance to interrupt the stalled probe
- **AND** bootstrap SHALL remain in `NeedsSetup`

#### Scenario: Cancellation during probe
- **WHEN** bootstrap is canceled or retried while the synthesis probe is in flight
- **THEN** the system SHALL call `stop()` on the `TextToSpeech` instance
- **AND** it SHALL delete the transient probe file
- **AND** it SHALL shut down the instance
- **AND** no voice gate SHALL be committed from the canceled path

### Requirement: Transient regular-file synthesis without engine-owned audible playback or persistence
The system SHALL synthesize each requested navigation phrase to a transient regular file using `TextToSpeech.synthesizeToFile` with a non-null utterance id. The system SHALL NOT call `TextToSpeech.speak()` or set `AudioAttributes` on the `TextToSpeech` instance at any point. The system SHALL delete each transient file immediately after reading its PCM content into memory, and SHALL NOT persist any synthesized audio to disk, cache, or backup-eligible storage. The system SHALL call `stop()` on the `TextToSpeech` instance before enqueuing a superseding synthesis so that no stale utterance from a prior navigation event can be rendered. The system SHALL ensure the transient file is a regular file, not a character device or pipe, so that engine seek operations succeed.

#### Scenario: On-demand synthesis to transient file
- **WHEN** a navigation announcement is requested for the current channel
- **THEN** the system SHALL call `synthesizeToFile` with the current phrase text, the retained voice, and a unique utterance id
- **AND** it SHALL NOT call `speak()` or any engine-owned playback method

#### Scenario: Transient file deleted after reading
- **WHEN** the synthesis callback reports completion and the system has read the PCM content into memory
- **THEN** the system SHALL delete the transient file immediately
- **AND** no synthesized audio SHALL remain on disk after the read completes

#### Scenario: No persistent storage of synthesized audio
- **WHEN** a phrase is synthesized and played through the existing app-owned playback path
- **THEN** the system SHALL NOT write any cache manifest, fingerprint, or WAV entry
- **AND** no synthesized navigation PCM SHALL survive the announcement lifecycle

#### Scenario: Stop before superseding enqueue prevents stale rendering
- **WHEN** a new navigation phrase is submitted while a prior synthesis is still in progress
- **THEN** the system SHALL call `stop()` on the `TextToSpeech` instance to interrupt and discard the prior utterance before enqueuing the new synthesis
- **AND** the prior utterance SHALL NOT be rendered or delivered to playback

#### Scenario: Transient file is a regular file
- **WHEN** the system creates a transient file for `synthesizeToFile`
- **THEN** the file SHALL be a regular file in the application's temporary or cache directory
- **AND** it SHALL NOT be a character device, pipe, or `/dev/null`

#### Scenario: Cancellation during synthesis
- **WHEN** the owning navigation operation is canceled while `synthesizeToFile` is in progress
- **THEN** the system SHALL call `stop()` on the `TextToSpeech` instance to interrupt and discard the current utterance
- **AND** it SHALL delete any partial transient file
- **AND** the canceled generation SHALL NOT deliver PCM to playback


### Requirement: synthesizeToFile immediate return classification
The system SHALL classify the immediate return value of `TextToSpeech.synthesizeToFile` before waiting for any callback. A return of `TextToSpeech.ERROR` SHALL be treated as an enqueue failure: the system SHALL unregister the operation, delete any created transient file, and SHALL NOT wait for a timeout or callback. A setup-phase probe `synthesizeToFile` returning `ERROR` SHALL be classified as a voice probe failure mapping to `NeedsSetup`. A runtime navigation `synthesizeToFile` returning `ERROR` SHALL be classified as an engine or service failure triggering the bounded recovery path. A return of `TextToSpeech.SUCCESS` SHALL mean the synthesis was accepted and queued only; final success SHALL still require a matching `onDone` callback with valid normalized output. The system SHALL NOT treat `SUCCESS` as proof of completed synthesis.

#### Scenario: synthesizeToFile returns ERROR during setup probe
- **WHEN** the setup-phase probe `synthesizeToFile` returns `ERROR`
- **THEN** the system SHALL unregister the probe operation and delete any created transient file
- **AND** it SHALL NOT wait for a callback or timeout
- **AND** it SHALL classify the failure as a voice probe failure
- **AND** bootstrap SHALL remain in `NeedsSetup`

#### Scenario: synthesizeToFile returns ERROR during runtime navigation
- **WHEN** a runtime navigation `synthesizeToFile` returns `ERROR`
- **THEN** the system SHALL unregister the operation and delete any created transient file
- **AND** it SHALL NOT wait for a callback or timeout
- **AND** it SHALL classify the failure as an engine or service failure
- **AND** it SHALL trigger the bounded recovery path

#### Scenario: synthesizeToFile returns SUCCESS means accepted only
- **WHEN** `synthesizeToFile` returns `SUCCESS`
- **THEN** the system SHALL treat the synthesis as accepted and queued
- **AND** it SHALL wait for the matching `onDone` callback
- **AND** it SHALL NOT treat `SUCCESS` as completed synthesis or deliver PCM until `onDone` fires and the output validates

#### Scenario: Runtime synthesis accepted but no terminal callback by deadline
- **WHEN** a runtime navigation `synthesizeToFile` returns `SUCCESS` but no `onDone` or `onError` fires before the configured callback deadline
- **THEN** the system SHALL call `stop()` on the `TextToSpeech` instance
- **AND** it SHALL unregister the operation and delete the transient file
- **AND** it SHALL classify the timeout as an engine or service failure
- **AND** it SHALL trigger the bounded recovery path

#### Scenario: Second timeout on retried synthesis after recovery
- **WHEN** the retried synthesis after a successful re-probe is accepted but no terminal callback fires before the configured deadline
- **THEN** the system SHALL classify the failure as retryable `BootstrapState.Failed`
- **AND** the system SHALL NOT attempt another reinitialization
- **AND** the system SHALL leave `Ready`

### Requirement: Callback format validation and normalization to non-empty 16 kHz mono PCM16
The system SHALL validate the audio format reported by the `UtteranceProgressListener.onBeginSynthesis` callback and/or the written file metadata. The system SHALL accept synthesis output in any of the following encodings: PCM 8-bit (`ENCODING_PCM_8BIT`), PCM 16-bit (`ENCODING_PCM_16BIT`), or PCM float (`ENCODING_PCM_FLOAT`). The system SHALL accept mono or stereo channel count and SHALL downmix stereo to mono by averaging the two channels. The system SHALL normalize accepted PCM to exactly 16 kHz mono PCM16 before returning a `RecordedPcm` for the existing app-owned playback path. The system SHALL resample accepted PCM to 16 kHz if the engine reports a different native sample rate, using the existing `TtsAudio.resample` linear interpolation. The system SHALL convert PCM 8-bit unsigned to PCM16 signed and SHALL convert PCM float to PCM16 by clamping source float samples to `[-1.0, 1.0]` and scaling/rounding to the signed PCM16 range `[-32768, 32767]`. The system SHALL reject empty PCM, a sample count of zero, an unsupported encoding other than the three accepted formats, an unparseable file format, or a channel count other than one or two as a synthesis failure.
The normalization pipeline SHALL decode accepted PCM8, PCM16, or PCM float WAV input to a mono normalized `FloatArray` in `[-1.0, 1.0]` — downmixing stereo to mono by averaging and converting unsigned PCM8 or signed PCM16 to float — then resample the `FloatArray` to 16 kHz using the existing `TtsAudio.resample` linear interpolation, then convert to signed PCM16 by clamping to `[-1.0, 1.0]` and scaling/rounding to `[-32768, 32767]` via `TtsAudio.f32ToPcm16`.

#### Scenario: Engine outputs 16 kHz mono PCM16
- **WHEN** `onBeginSynthesis` reports sample rate 16000, audio format `ENCODING_PCM_16BIT`, and channel count 1
- **AND** `onDone` fires with a non-empty file
- **THEN** the system SHALL read the file as mono PCM16 at 16 kHz
- **AND** it SHALL return a non-empty `RecordedPcm` with `sampleRate = 16000` and a positive sample count

#### Scenario: Engine outputs a different sample rate
- **WHEN** `onBeginSynthesis` reports a sample rate other than 16000 with mono PCM16
- **THEN** the system SHALL resample the PCM to 16 kHz using linear interpolation
- **AND** the resulting `RecordedPcm` SHALL report `sampleRate = 16000`

#### Scenario: Engine outputs PCM 8-bit
- **WHEN** `onBeginSynthesis` reports audio format `ENCODING_PCM_8BIT`
- **THEN** the system SHALL convert the unsigned 8-bit samples to signed PCM16
- **AND** it SHALL normalize the result to 16 kHz mono PCM16

#### Scenario: Engine outputs PCM float
- **WHEN** `onBeginSynthesis` reports audio format `ENCODING_PCM_FLOAT`
- **THEN** the system SHALL clamp the source float samples to `[-1.0, 1.0]` and scale/round to signed PCM16 range `[-32768, 32767]`
- **AND** it SHALL normalize the result to 16 kHz mono PCM16

#### Scenario: Engine outputs stereo
- **WHEN** `onBeginSynthesis` reports channel count 2
- **THEN** the system SHALL downmix stereo to mono by averaging the two channels
- **AND** the resulting `RecordedPcm` SHALL report a mono signal at 16 kHz

#### Scenario: Unsupported encoding rejected
- **WHEN** `onBeginSynthesis` or the written file reports an audio format other than `ENCODING_PCM_8BIT`, `ENCODING_PCM_16BIT`, or `ENCODING_PCM_FLOAT`
- **THEN** the system SHALL classify this as a synthesis failure
- **AND** it SHALL NOT attempt to decode or normalize the output

#### Scenario: Unsupported channel count rejected
- **WHEN** `onBeginSynthesis` or the written file reports a channel count other than 1 or 2
- **THEN** the system SHALL classify this as a synthesis failure
- **AND** it SHALL NOT attempt channel conversion

#### Scenario: Empty PCM rejected
- **WHEN** the synthesis callback fires `onDone` but the file is empty or contains zero samples
- **THEN** the system SHALL classify this as a synthesis failure
- **AND** it SHALL NOT deliver an empty `RecordedPcm` to playback

#### Scenario: Unparseable file rejected
- **WHEN** the written file cannot be decoded or its header is malformed
- **THEN** the system SHALL classify this as a synthesis failure
- **AND** it SHALL delete the transient file

#### Scenario: Cancellation during format validation
- **WHEN** the owning navigation operation is canceled after synthesis completes but before format validation and PCM reading finish
- **THEN** the system SHALL NOT deliver the PCM to playback
- **AND** it SHALL delete the transient file

### Requirement: Unique utterance generations and stale callback rejection
The system SHALL use a two-tier callback ownership model. First, every synthesis request — whether a setup-phase probe, a runtime recovery probe, or a navigation announcement — SHALL be assigned a unique utterance id, and each `UtteranceProgressListener` callback SHALL resolve only against the live operation and `TextToSpeech` instance that issued it. A callback from an old, shut-down, or unregistered instance SHALL be rejected. Second, navigation announcement PCM delivery SHALL additionally require that the utterance's `navigationGeneration` equals the current authoritative navigation generation; a callback whose `navigationGeneration` does not match SHALL NOT deliver PCM to playback. Setup-phase and recovery probes SHALL use bootstrap and recovery attempt ownership respectively, independent of the navigation generation, so that advancing the navigation generation during an in-flight recovery does not invalidate the active probe's callbacks. The system SHALL NOT deliver stale PCM to playback under any circumstance.

#### Scenario: Current utterance callback accepted and navigation generation matches
- **WHEN** the `UtteranceProgressListener` fires `onDone` for the utterance id matching the live operation that issued it
- **AND** the utterance's `navigationGeneration` equals the current authoritative navigation generation
- **THEN** the system SHALL read and validate the PCM from the transient file
- **AND** it SHALL deliver the normalized `RecordedPcm` to the owning playback path

#### Scenario: Stale navigation generation callback rejected
- **WHEN** the `UtteranceProgressListener` fires `onDone` for a live-instance utterance whose `navigationGeneration` does not match the authoritative navigation generation
- **THEN** the system SHALL discard the callback and its file
- **AND** it SHALL delete the stale transient file
- **AND** it SHALL NOT deliver the stale PCM to playback

#### Scenario: Old-instance callback rejected
- **WHEN** the `UtteranceProgressListener` fires `onDone` for an utterance id belonging to an old, shut-down, or unregistered `TextToSpeech` instance
- **THEN** the system SHALL reject the callback
- **AND** it SHALL NOT deliver PCM from the old instance to playback

#### Scenario: Late callback from a canceled synthesis
- **WHEN** a synthesis is canceled via `stop()` but the engine fires `onDone` or `onAudioAvailable` afterward for the old utterance id
- **THEN** the system SHALL reject the callback because its operation is no longer live
- **AND** no PCM from the canceled synthesis SHALL reach playback

#### Scenario: Callback fires onStop for an interrupted utterance
- **WHEN** `stop()` interrupts a prior utterance and `onStop` fires for the old utterance id
- **THEN** the system SHALL discard the stopped callback without delivering PCM
- **AND** it SHALL NOT treat `onStop` as a synthesis success or failure for the current operation

#### Scenario: Recovery probe callbacks are independent of navigation generation
- **WHEN** a runtime reinitialization creates a new `TextToSpeech` instance and issues a recovery probe synthesis with a unique utterance id under recovery attempt ownership
- **AND** a newer navigation request advances the authoritative navigation generation while the probe is in flight
- **THEN** the probe callback SHALL resolve against the recovery attempt that issued it, not against the navigation generation
- **AND** the probe SHALL NOT be rejected or invalidated by the navigation generation advance
- **AND** the recovery SHALL be able to complete and retry the newest pending navigation announcement

#### Scenario: Service teardown invalidates recovery probe attempt
- **WHEN** the renderer or foreground service is torn down or a bootstrap retry discards the prior attempt while a recovery probe is in flight
- **THEN** the recovery attempt ownership SHALL be invalidated
- **AND** any late probe callback from the shut-down instance SHALL be rejected
- **AND** no probe PCM SHALL be delivered

### Requirement: Latest-wins synthesis, recovery, and playback ownership

The system SHALL enforce strict latest-wins across synthesis, recovery, and active navigation playback. A newer navigation request SHALL cancel and supersede any in-flight synthesis in this exact order: first publish the new authoritative navigation generation, then call `stop()` on the `TextToSpeech` instance to interrupt the prior utterance, then `cancelAndJoin` the prior job's cleanup, then launch the new synthesis. This order guarantees the prior synthesis's `navigationGeneration` no longer matches before its callback can fire. A newer navigation request arriving during an in-flight bounded recovery SHALL NOT cancel or restart the recovery attempt, because the prior `TextToSpeech` instance is shut down and no retained or recovered instance exists until recovery finishes; the recovery continues once to completion. The newer request SHALL advance the authoritative navigation generation and replace only the latest pending navigation generation and phrase text, but SHALL NOT invalidate the active recovery probe's callbacks, because recovery probes use recovery attempt ownership independent of the navigation generation. Only the newest pending announcement SHALL be retried on successful recovery. Navigation playback ownership SHALL be admitted exclusively through the existing `HostAudioCoordinator` single-owner admission and the existing `ModePlaybackRouteResolver` route acquisition with `setPreferredDevice`. A skip-driven playback cancel SHALL preserve the existing `PlaybackCompletion.ExplicitlySkipped` result through `HostAudioCoordinator` so that skip-driven teardown is distinguishable from natural completion. Navigation SHALL NOT preempt an active capture operation or an unrelated channel-content playback; it SHALL respect the existing half-duplex admission arbitration.

#### Scenario: Newer navigation cancels in-flight synthesis
- **WHEN** a second navigation announcement is requested while a prior synthesis is in progress
- **THEN** the system SHALL publish the new authoritative navigation generation first
- **AND** it SHALL call `stop()` on the `TextToSpeech` instance to interrupt the prior utterance
- **AND** it SHALL `cancelAndJoin` the prior job's cleanup before launching the new synthesis
- **AND** only the newer request SHALL proceed to synthesis and playback

#### Scenario: Newer navigation during in-flight recovery replaces pending text only
- **WHEN** a second navigation announcement is requested while a bounded runtime recovery attempt is in progress
- **THEN** the system SHALL NOT cancel or restart the recovery attempt
- **AND** it SHALL advance the authoritative navigation generation and replace only the latest pending navigation generation and phrase text
- **AND** the recovery SHALL continue once to completion
- **AND** only the newest pending announcement SHALL be retried on successful recovery

#### Scenario: Sequential requests during recovery retry only the newest
- **WHEN** an Alpha channel announcement triggers recovery and then Bravo and Journal announcements are requested in sequence during that in-flight recovery
- **THEN** the recovery SHALL continue once without restart or thrash
- **AND** each newer request SHALL advance the authoritative navigation generation and replace the pending phrase text
- **AND** on successful recovery only the Journal announcement SHALL be retried
- **AND** the Alpha and Bravo announcements SHALL be discarded without playback

#### Scenario: Latest-wins does not preempt active capture
- **WHEN** a navigation announcement is requested while a PTT capture operation owns the host audio coordinator
- **THEN** the navigation playback SHALL NOT preempt or interrupt the capture operation
- **AND** the system SHALL respect the existing `HostAudioCoordinator` admission arbitration

#### Scenario: Latest-wins does not preempt unrelated playback
- **WHEN** a navigation announcement is requested while an unrelated channel-content playback is active
- **THEN** the navigation playback SHALL NOT preempt the active playback
- **AND** the system SHALL respect the existing `HostAudioCoordinator` single-owner admission

#### Scenario: Synthesis completion races with supersession
- **WHEN** a prior synthesis completes and fires `onDone` concurrently with a newer request advancing the authoritative navigation generation
- **THEN** the prior callback SHALL NOT deliver PCM because its `navigationGeneration` no longer matches
- **AND** only the newer navigation generation SHALL be eligible for playback

#### Scenario: Playback completion before next synthesis
- **WHEN** a navigation playback is completing its `AudioTrack` cleanup and a newer navigation request arrives
- **THEN** the newer request SHALL wait for the `HostAudioCoordinator` to release the prior owner before admitting its playback
- **AND** the existing terminal teardown ordering SHALL be preserved

### Requirement: Current phrase text resolution at request time
The system SHALL resolve navigation phrase text from the current channel catalogue at request time, not from a pre-computed vocabulary, cached text map, or persisted phrase store. For a channel name or selection announcement, the system SHALL read the current `ChannelDefinition.name` from the live `catalogueState` snapshot for the active channel id. For the menu entry phrase, the system SHALL use the fixed text "Channels". The system SHALL NOT retain or reuse phrase text across launches or across catalogue mutations; each announcement request SHALL resolve text from the live catalogue.

#### Scenario: Channel name announcement resolves current text
- **WHEN** a channel name announcement is requested for the active channel id
- **THEN** the system SHALL read the current `ChannelDefinition.name` for that id from the live `catalogueState`
- **AND** it SHALL synthesize that exact text

#### Scenario: Channel selection announcement resolves current text
- **WHEN** a channel selection announcement is requested for the active channel id
- **THEN** the system SHALL read the current `ChannelDefinition.name` and synthesize the selection phrase using the current name
- **AND** it SHALL NOT use a stale or pre-computed name

#### Scenario: Menu entry phrase uses fixed text
- **WHEN** the menu entry announcement is requested
- **THEN** the system SHALL synthesize the fixed text "Channels"

#### Scenario: Channel renamed between requests
- **WHEN** a channel is renamed and a subsequent navigation announcement is requested for that channel
- **THEN** the system SHALL resolve and synthesize the new name from the live catalogue
- **AND** it SHALL NOT play audio synthesized from the prior name

#### Scenario: Channel removed from catalogue
- **WHEN** a navigation announcement is requested for a channel id that no longer exists in the catalogue
- **THEN** the system SHALL NOT synthesize or play audio for that id
- **AND** it SHALL NOT fall back to stale cached text or PCM

#### Scenario: Catalogue is empty
- **WHEN** the channel catalogue contains no definitions and a channel-name announcement is requested
- **THEN** the system SHALL NOT synthesize or play audio
- **AND** no crash or unhandled exception SHALL occur

### Requirement: One bounded runtime reinitialization and newest-only retry
The system SHALL classify runtime failures into two exhaustive paths. An engine or service failure — runtime `onError`, synthesis timeout, or TTS service loss — SHALL trigger at most one `TextToSpeech` reinitialization and offline-voice probe. If the re-probe reports an unusable engine, voice, or probe (engine unavailable, engine init failure, voice missing, or voice probe failure), the system SHALL transition bootstrap to `NeedsSetup` with a user-resolvable Android TTS settings or engine install action. If the re-probe succeeds but the retried synthesis fails, the system SHALL classify the failure as retryable `BootstrapState.Failed`. An infrastructure failure — transient file I/O failure, decoder failure, PCM normalization failure, or empty PCM output — SHALL be classified as retryable `BootstrapState.Failed` without any `TextToSpeech` reinitialization. The system SHALL NOT attempt more than one reinitialization per engine or service failure. The system SHALL retry only the newest pending navigation generation once, discarding any older pending navigation requests. A newer navigation request alone SHALL only replace the latest pending navigation generation and phrase text and SHALL NOT cancel or restart an in-flight recovery attempt. Recovery SHALL be canceled only by renderer or service teardown or bootstrap attempt discard. Every failed path SHALL leave `Ready` and SHALL drop the current navigation generation. A successful recovery SHALL leave bootstrap at `Ready`. The system SHALL clear a `Recovering` state in a `finally` block on all terminal paths, including successful recovery, failed re-probe, failed retried synthesis, infrastructure failure, and recovery cancellation by teardown or discard. The system SHALL NOT play a degraded beep or fallback tone under any runtime failure classification.
The recovery budget is per failure chain: a retry synthesis failure within the same recovery chain SHALL NOT trigger a second recovery. A later independent announcement that fails after a fully successful recovery and retry SHALL be a new failure eligible for one new bounded recovery.

#### Scenario: Engine or service failure triggers one reinitialization
- **WHEN** a runtime synthesis callback fires `onError`, the synthesis times out, or the TTS service is lost
- **THEN** the system SHALL attempt one `TextToSpeech` reinitialization and offline-voice probe
- **AND** it SHALL retry only the newest pending announcement after the probe succeeds

#### Scenario: Successful reinitialization retries newest announcement and stays Ready
- **WHEN** the reinitialized `TextToSpeech` instance passes the offline-voice probe and the retried synthesis succeeds
- **THEN** the system SHALL synthesize the newest pending announcement text using the recovered instance
- **AND** older pending announcements SHALL be discarded
- **AND** bootstrap SHALL remain at `Ready`

#### Scenario: Re-probe unusable transitions to NeedsSetup
- **WHEN** the single permitted reinitialization re-probe reports an engine unavailable, engine init failure, voice missing, or voice probe failure
- **THEN** the system SHALL transition bootstrap to `NeedsSetup` with a user-resolvable Android TTS settings or engine install action
- **AND** the system SHALL leave `Ready`
- **AND** the system SHALL NOT play a degraded beep

#### Scenario: Re-probe succeeds but retried synthesis fails as retryable Failed
- **WHEN** the reinitialization and re-probe succeed but the retried synthesis fails
- **THEN** the system SHALL classify the failure as retryable `BootstrapState.Failed`
- **AND** the system SHALL leave `Ready`
- **AND** the system SHALL NOT transition to `NeedsSetup`
- **AND** the system SHALL NOT play a degraded beep

#### Scenario: Infrastructure failure is retryable Failed without TTS reinitialization
- **WHEN** a runtime synthesis completes via `onDone` but fails due to a transient file I/O failure, decoder failure, PCM normalization failure, or empty PCM output
- **THEN** the system SHALL classify the failure as retryable `BootstrapState.Failed`
- **AND** the system SHALL NOT attempt any `TextToSpeech` reinitialization
- **AND** the system SHALL leave `Ready`
- **AND** the system SHALL NOT play a degraded beep

#### Scenario: Recovery failure leaves Ready
- **WHEN** any bounded recovery failure occurs, whether classified as unusable, retryable, or infrastructure
- **THEN** the system SHALL leave bootstrap `Ready`
- **AND** the user SHALL NOT hear a degraded beep or fallback tone
- **AND** the failed announcement SHALL be dropped
- **AND** the current navigation generation SHALL be dropped

#### Scenario: Only newest pending announcement is retried
- **WHEN** multiple navigation announcements are pending and a runtime failure triggers recovery
- **THEN** the system SHALL retry only the newest pending request
- **AND** all older pending requests SHALL be discarded without playback

#### Scenario: Second engine failure after recovery is not retried
- **WHEN** the recovered instance produces a second engine or service failure for the retried announcement
- **THEN** the system SHALL NOT attempt another reinitialization
- **AND** the failed announcement SHALL be dropped
- **AND** the system SHALL leave `Ready`

#### Scenario: Later independent failure after successful recovery is eligible for new recovery
- **WHEN** a fully successful recovery and retry have completed and a later independent navigation announcement subsequently fails with an engine or service failure
- **THEN** the system SHALL treat it as a new failure chain eligible for one new bounded reinitialization and re-probe
- **AND** the prior recovery budget SHALL NOT prevent the new recovery
- **AND** a retry synthesis failure within the same new chain SHALL NOT trigger a third recovery

#### Scenario: Recovery canceled only by renderer or service teardown
- **WHEN** the renderer or foreground service is torn down or a bootstrap retry discards the prior attempt while the bounded reinitialization and probe are in progress
- **THEN** the system SHALL cancel the recovery attempt
- **AND** it SHALL shut down the reinitialized instance if the probe has not completed
- **AND** the recovery attempt ownership SHALL be invalidated so any pending probe callback from the shut-down instance is rejected
- **AND** the `Recovering` state SHALL be cleared in a `finally` block on this terminal path

### Requirement: Atomic recovery handoff
While a bounded reinitialization and probe are in flight, the system SHALL coalesce all pending navigation requests into one latest pending entry. On probe success, the system SHALL perform the recovery handoff under one mutex atomically: publish the recovered `TextToSpeech` instance, clear the `Recovering` state, snapshot the newest pending navigation generation and phrase text, and start exactly one retry synthesis tagged with a no-second-recovery-on-failure flag. A navigation request racing after the retry synthesis is enqueued either is included in the atomic snapshot or normally supersedes the retry via the standard generation-advance → `stop()` → `cancelAndJoin` → new-job sequence. A stale retry that has been superseded is not a failure and does not consume the recovery budget. The newest pending announcement SHALL NOT be lost under any racing condition.

#### Scenario: Atomic handoff on probe success
- **WHEN** the reinitialization probe succeeds while navigation requests are pending
- **THEN** the system SHALL atomically publish the recovered instance, clear `Recovering`, snapshot the newest pending navigation generation and phrase text, and start one retry synthesis tagged no-second-recovery-on-failure
- **AND** the handoff SHALL occur under one mutex so that no request can observe a half-published recovered instance

#### Scenario: Request races after retry synthesis enqueue
- **WHEN** a new navigation request arrives after the retry synthesis has been enqueued but before it completes
- **AND** the new request was not included in the atomic snapshot
- **THEN** the new request SHALL normally supersede the retry by advancing the authoritative navigation generation, calling `stop()`, `cancelAndJoin`-ing the retry job, and launching a new synthesis
- **AND** the superseded stale retry SHALL NOT be classified as a failure and SHALL NOT consume the recovery budget
- **AND** the newest pending announcement SHALL NOT be lost

#### Scenario: Request included in atomic snapshot
- **WHEN** a navigation request arrives during the mutex-protected handoff and is included in the atomic snapshot
- **THEN** the retry synthesis SHALL use the request's phrase text and navigation generation
- **AND** no duplicate or stale synthesis SHALL be started

#### Scenario: Coalesced pending requests during recovery
- **WHEN** multiple navigation requests arrive while reinitialization and probe are in flight
- **THEN** the system SHALL coalesce them into one latest pending entry
- **AND** only the newest generation and phrase text SHALL be retried on probe success
- **AND** all older pending requests SHALL be discarded without playback

### Requirement: Renderer lifecycle stop, shutdown, and cleanup
The system SHALL call `TextToSpeech.stop()` to interrupt any in-flight synthesis and discard queued utterances when a navigation operation is canceled or superseded. The system SHALL call `TextToSpeech.shutdown()` when the renderer is no longer needed, including on service teardown, bootstrap retry discard, and bounded recovery failure. The system SHALL delete all transient synthesis files during cleanup, including files from completed, canceled, and failed syntheses. The system SHALL NOT leak a `TextToSpeech` instance, an `AudioTrack`, or a transient file beyond the renderer lifecycle.

#### Scenario: Stop interrupts in-flight synthesis on cancellation
- **WHEN** a navigation operation is canceled while its `synthesizeToFile` call is in progress
- **THEN** the system SHALL call `stop()` on the `TextToSpeech` instance
- **AND** the in-flight utterance SHALL be interrupted and discarded from the engine queue

#### Scenario: Shutdown releases the TTS instance on service teardown
- **WHEN** the foreground service is destroyed or the renderer is no longer needed
- **THEN** the system SHALL call `shutdown()` on the retained `TextToSpeech` instance
- **AND** the instance SHALL be unusable after shutdown
- **AND** no further synthesis or voice query SHALL be attempted on that instance

#### Scenario: Shutdown during bootstrap retry discard
- **WHEN** a bootstrap retry discards the prior attempt and its controllers
- **THEN** the system SHALL call `shutdown()` on the prior `TextToSpeech` instance
- **AND** the replacement attempt SHALL construct a fresh instance

#### Scenario: Cleanup deletes all transient files
- **WHEN** the renderer is shut down or a navigation operation completes, is canceled, or fails
- **THEN** the system SHALL delete every transient file created by that operation
- **AND** no transient synthesis file SHALL remain on disk after cleanup

#### Scenario: Stop and shutdown on bounded recovery failure
- **WHEN** the bounded recovery reinitialization and probe fail
- **THEN** the system SHALL call `shutdown()` on the reinitialized instance
- **AND** it SHALL delete any probe or synthesis transient file
- **AND** no leaked instance or file SHALL remain

#### Scenario: Cancellation race with shutdown
- **WHEN** a navigation operation is canceled concurrently with a service teardown `shutdown()` call
- **THEN** the system SHALL ensure `stop()` and `shutdown()` are called at most once each on the instance
- **AND** no late callback SHALL deliver PCM after shutdown

### Requirement: Failure classification
The system SHALL classify every Android TTS failure into exactly one of the following categories. Setup-phase failures — engine unavailable (no installed engine), engine init failure (`onInit(ERROR)` or init timeout), voice missing (no valid offline English voice discovered), and voice probe failure (probe synthesis produced empty, undecodable, or timed-out audio) — SHALL map to `NeedsSetup` with a user-resolvable Android TTS settings or engine install action and SHALL prevent core native STT and Supertonic initialization. Runtime failures SHALL be classified into two exhaustive paths. An engine or service failure — runtime `onError`, synthesis timeout, or TTS service loss — SHALL trigger one bounded `TextToSpeech` reinitialization and re-probe; if the re-probe reports an unusable engine, voice, or probe, the system SHALL transition bootstrap to `NeedsSetup`; if the re-probe succeeds but the retried synthesis fails, the system SHALL classify it as retryable `BootstrapState.Failed`. An infrastructure failure — transient file I/O failure, decoder failure, PCM normalization failure, or empty PCM output — SHALL be classified as retryable `BootstrapState.Failed` without any `TextToSpeech` reinitialization. Every failed runtime path SHALL leave `Ready` and SHALL drop the current navigation generation. A successful recovery SHALL leave bootstrap at `Ready`. The system SHALL NOT play a degraded beep or fallback tone under any failure classification. The system SHALL NOT play stale, partial, or empty PCM under any failure classification.

#### Scenario: Engine unavailable classified as NeedsSetup
- **WHEN** no TTS engine is installed or the default engine package is absent
- **THEN** the system SHALL classify the failure as engine unavailable
- **AND** bootstrap SHALL remain in `NeedsSetup` with an engine install action
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: Engine init failure classified as NeedsSetup
- **WHEN** `onInit` fires with `ERROR` or the init listener times out
- **THEN** the system SHALL classify the failure as engine init failure
- **AND** bootstrap SHALL remain in `NeedsSetup` with an Android TTS settings action
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: Voice missing classified as NeedsSetup
- **WHEN** no valid offline English voice is discovered
- **THEN** the system SHALL classify the failure as voice missing
- **AND** bootstrap SHALL remain in `NeedsSetup` with an offline English voice install action
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: Voice probe failure classified as NeedsSetup
- **WHEN** the probe synthesis produces empty, undecodable, or timed-out audio
- **THEN** the system SHALL classify the failure as voice probe failure
- **AND** bootstrap SHALL remain in `NeedsSetup` with an Android TTS settings action
- **AND** core native STT and Supertonic initialization SHALL NOT proceed

#### Scenario: Engine or service failure triggers one reinitialization
- **WHEN** a runtime `onError`, synthesis timeout, or TTS service loss occurs during an active navigation announcement
- **THEN** the system SHALL classify the failure as an engine or service failure
- **AND** it SHALL trigger one bounded `TextToSpeech` reinitialization and re-probe

#### Scenario: Unusable re-probe transitions to NeedsSetup
- **WHEN** the single permitted reinitialization re-probe reports an engine unavailable, engine init failure, voice missing, or voice probe failure
- **THEN** the system SHALL classify the failure as a runtime reinitialization failure with an unusable cause
- **AND** bootstrap SHALL transition to `NeedsSetup` with a user-resolvable action
- **AND** the system SHALL leave `Ready`

#### Scenario: Re-probe succeeds but retried synthesis fails as retryable Failed
- **WHEN** the reinitialization and re-probe succeed but the retried synthesis fails
- **THEN** the system SHALL classify the failure as retryable `BootstrapState.Failed`
- **AND** the system SHALL leave `Ready`
- **AND** the system SHALL NOT transition to `NeedsSetup`

#### Scenario: Infrastructure failure is retryable Failed without TTS reinitialization
- **WHEN** a runtime synthesis completes via `onDone` but fails due to a transient file I/O failure, decoder failure, PCM normalization failure, or empty PCM output
- **THEN** the system SHALL classify the failure as retryable `BootstrapState.Failed`
- **AND** the system SHALL NOT attempt any `TextToSpeech` reinitialization
- **AND** the system SHALL leave `Ready`

#### Scenario: Exhausted retry has no further recovery
- **WHEN** the retried synthesis after a successful re-probe fails or a second engine or service failure occurs after recovery
- **THEN** the system SHALL NOT attempt another reinitialization
- **AND** the system SHALL leave `Ready`
- **AND** the failed announcement SHALL be dropped
- **AND** the current navigation generation SHALL be dropped

#### Scenario: No degraded beep under any failure classification
- **WHEN** any failure classification is determined for a synthesis that has partially or fully completed
- **THEN** the system SHALL NOT play a degraded beep or any fallback tone
- **AND** the failed announcement SHALL be dropped or retried only per the bounded recovery rules

#### Scenario: No stale or empty PCM played under any classification
- **WHEN** any failure classification is determined for a synthesis that has partially completed
- **THEN** the system SHALL NOT play the partial, stale, or empty PCM
- **AND** the transient file SHALL be deleted
- **AND** the failed announcement SHALL be dropped or retried only per the bounded recovery rules

### Requirement: Setup action uses public Android TTS install and settings intents
The system SHALL expose a user-resolvable setup action for every `NeedsSetup` classification. The system SHALL target `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` with `Intent.setPackage` set to the active or default TTS engine package name, then resolve via `PackageManager.resolveActivity`; this intent is the only public Android TTS voice-data install UI. If `resolveActivity` with `setPackage` fails or no default TTS engine is installed, the system SHALL fall back to `Settings.ACTION_SETTINGS` with an explicit instruction to install or enable a TTS engine and download an offline English voice. The system SHALL NOT use the undocumented `com.android.settings.TTS_SETTINGS` activity or `Settings.ACTION_VOICE_INPUT_SETTINGS`. Returning from either intent SHALL trigger a full re-probe of the engine, voice, and synthesis gate.

#### Scenario: ACTION_INSTALL_TTS_DATA resolves and launches
- **WHEN** the system classifies a setup-phase failure as `NeedsSetup` and `resolveActivity` succeeds for `ACTION_INSTALL_TTS_DATA` with `Intent.setPackage` set to the active or default TTS engine
- **THEN** the system SHALL launch the `setPackage`-targeted `ACTION_INSTALL_TTS_DATA` intent
- **AND** it SHALL NOT use any undocumented settings activity

#### Scenario: No handler falls back to Settings.ACTION_SETTINGS
- **WHEN** `resolveActivity` with `setPackage` fails for `ACTION_INSTALL_TTS_DATA` or no default TTS engine is installed
- **THEN** the system SHALL fall back to `Settings.ACTION_SETTINGS`
- **AND** it SHALL present an explicit instruction to install or enable a TTS engine and download an offline English voice
- **AND** it SHALL NOT use `com.android.settings.TTS_SETTINGS` or `Settings.ACTION_VOICE_INPUT_SETTINGS`

#### Scenario: Returning from setup action triggers re-probe
- **WHEN** the user returns from either `ACTION_INSTALL_TTS_DATA` or `Settings.ACTION_SETTINGS`
- **THEN** the system SHALL trigger a full re-probe of the engine, voice selection, and synthesis probe
- **AND** it SHALL re-evaluate the hard bootstrap readiness gate with the new TTS state

#### Scenario: Undocumented settings activities are never used
- **WHEN** the system constructs any setup action intent
- **THEN** it SHALL NOT target `com.android.settings.TTS_SETTINGS`
- **AND** it SHALL NOT target `Settings.ACTION_VOICE_INPUT_SETTINGS`
- **AND** it SHALL use only the two public intents in the stated priority order

### Requirement: Behavior-preserving navigation TTS decomposition
The system SHALL separate navigation TTS contracts, identity declarations, deterministic offline voice selection, and WAV normalization from the stateful `NavigationTtsEngine` implementation without changing declaration names, package membership, visibility, signatures, defaults, result shapes, or observable runtime behavior. `NavigationTtsEngine` SHALL remain the sole mutable owner of Android `TextToSpeech` instances, coroutine scopes and jobs, mutex-protected state, navigation generations, pending-operation registry transitions, recovery, transient files, and stop/shutdown sequencing.

#### Scenario: Existing caller uses decomposed contracts
- **WHEN** an existing production or test caller constructs `NavigationTtsEngine`, supplies `TextToSpeechFactory` or `StateLossCallback`, or consumes preparation or synthesis results after decomposition
- **THEN** the caller SHALL use the same package-qualified declaration names and signatures as before the decomposition
- **AND** no compatibility alias, forwarding declaration, feature flag, or alternate engine implementation SHALL be required

#### Scenario: Stateful request behavior remains unchanged
- **WHEN** prepare, navigation synthesis, direct PCM playback, supersession, cancellation, recovery, or shutdown executes after the declarations are decomposed
- **THEN** callback acceptance, callback rejection, result classification, state-loss notification, cancellation propagation, and resource-action ordering SHALL match the pre-decomposition behavior
- **AND** each `TextToSpeech` instance, transient file, pending operation, playback job, and recovery attempt SHALL retain exactly one authoritative owner

#### Scenario: Late callback remains non-authoritative
- **WHEN** a callback arrives for an unregistered operation, an old engine epoch, a superseded navigation generation, or an engine that has already been shut down
- **THEN** the decomposed implementation SHALL reject it under the same ownership rules as the pre-decomposition implementation
- **AND** it SHALL NOT deliver stale PCM or create a second terminal side effect

#### Scenario: Factory construction failure remains equivalent
- **WHEN** the injected `TextToSpeechFactory` fails while constructing an engine instance
- **THEN** the resulting failure propagation or classification and cleanup actions SHALL match the characterized pre-decomposition behavior
- **AND** the structural extraction SHALL NOT introduce a fallback engine or retry path

### Requirement: Deterministic policy equivalence
Extracted offline voice selection and WAV normalization SHALL produce results equivalent to the pre-decomposition implementation for the same inputs. Equivalence SHALL include selected voice identity and selection failure category; normalized sample rate and exact PCM sample values; typed unsupported-format, unsupported-channel, empty-PCM, malformed-input, and file-I/O failures; and all externally visible `RecordedPcm` fields.

#### Scenario: Offline voice candidates are evaluated after extraction
- **WHEN** the extracted selector receives a fixed installed-voice set and fixed `isLanguageAvailable` and `setVoice` results
- **THEN** it SHALL accept and reject the same candidates as the pre-decomposition selector
- **AND** it SHALL apply latency, quality, locale-tag, and voice-name ordering identically
- **AND** it SHALL return the same selected voice or typed failure

#### Scenario: Supported WAV fixture is normalized after extraction
- **WHEN** the extracted normalizer receives a fixed supported mono or stereo PCM8, PCM16, or PCM-float WAV fixture
- **THEN** it SHALL produce a non-empty mono `RecordedPcm` at 16 kHz with the exact sample values produced before decomposition
- **AND** it SHALL preserve the existing downmix, linear-resampling, clamping, scaling, and rounding behavior

#### Scenario: Invalid WAV fixture is classified after extraction
- **WHEN** the extracted normalizer receives unsupported encoding, unsupported channel count, empty PCM, malformed input, or a file-I/O failure
- **THEN** it SHALL return the same typed renderer-infrastructure failure as the pre-decomposition implementation
- **AND** it SHALL NOT emit playable PCM

#### Scenario: Direct PCM request overlaps stateful engine work
- **WHEN** `requestPcm` overlaps an active synthesis, probe, recovery, supersession, cancellation, or shutdown boundary represented by the existing engine harness
- **THEN** job ownership, terminal result, playback delivery, and cleanup ordering SHALL match the characterized pre-decomposition behavior
- **AND** extracted deterministic policies SHALL NOT acquire independent coroutine or lifecycle ownership