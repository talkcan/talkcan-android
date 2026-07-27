# Runtime Model Download

## Purpose

Define runtime model distribution, integrity verification, versioning, and download progress for the setup flow.

## Requirements

### Requirement: model-hashes.json is bundled in APK

The system SHALL bundle a `model-hashes.json` file in APK assets that maps each model set to its version, HuggingFace repository, and per-file SHA-256 hashes.

#### Scenario: Manifest present in APK
- **WHEN** the APK is built
- **THEN** `model-hashes.json` is present in the APK assets directory

#### Scenario: Manifest format
- **WHEN** the manifest is read
- **THEN** it contains entries for `parakeet-tdt-0.6b-v3-int8` and `supertonic-3`, each with a `version` string, `repo` identifier, and `files` map from filename to `sha256:<hash>` string

### Requirement: Model files are not bundled in APK

The system SHALL NOT bundle ONNX model files, voice style JSONs, vocab files, or config JSONs inside the APK.

#### Scenario: No model assets in APK
- **WHEN** the APK is inspected
- **THEN** no files matching `*.onnx`, `vocab.txt`, or voice style `*.json` are present in the APK assets

### Requirement: Models are downloaded at runtime from HuggingFace

The system SHALL download model files from HuggingFace Hub at runtime using the repository identifiers in the hash manifest.

#### Scenario: Download STT models
- **WHEN** the model download step runs for the parakeet model set
- **THEN** files are downloaded from `https://huggingface.co/smcleod/parakeet-tdt-0.6b-v3-int8/resolve/main/{filename}` using the `huggingface.co` host

#### Scenario: Download TTS models
- **WHEN** the model download step runs for the supertonic model set
- **THEN** files are downloaded from `https://huggingface.co/Supertone/supertonic-3/resolve/main/{filePath}` where `{filePath}` corresponds to the HuggingFace subdirectory path (e.g., `onnx/duration_predictor.onnx`, `voice_styles/M1.json`)

#### Scenario: Files saved to correct directory
- **WHEN** a model file is downloaded
- **THEN** it is saved to `{filesDir}/{modelSetName}/{localFilename}` where `modelSetName` is `parakeet-tdt-0.6b-v3-int8` or `supertonic-3` and `localFilename` matches the flat layout expected by the native bridge

### Requirement: SHA-256 verification after download

The system SHALL compute the SHA-256 hash of each downloaded file and verify it against the bundled manifest before considering the model set complete.

#### Scenario: Hash matches
- **WHEN** a downloaded file's SHA-256 matches the manifest
- **THEN** the file is considered valid and the next file is downloaded

#### Scenario: Hash mismatch
- **WHEN** a downloaded file's SHA-256 does not match the manifest
- **THEN** the file is re-downloaded (up to a configurable retry limit, default 3)

### Requirement: Download progress reporting

The system SHALL report download progress (bytes downloaded vs total bytes per file, and which file is currently downloading) so the setup screen can display progress bars.

#### Scenario: Per-file progress
- **WHEN** a model file is downloading
- **THEN** the downloader emits progress callbacks with bytes read and total bytes for that file

#### Scenario: Multi-file progress
- **WHEN** multiple files in a model set are downloaded sequentially
- **THEN** the progress indicates which file is currently downloading and the overall percentage

### Requirement: Resumable downloads

The system SHALL support resuming interrupted downloads using HTTP `Range` headers.

#### Scenario: Resume partial download
- **WHEN** a download was interrupted and a partial file exists on disk
- **THEN** the downloader sends a `Range` header with the existing file's byte size and appends the remaining content

#### Scenario: Server supports range requests
- **WHEN** the HuggingFace server responds with `206 Partial Content`
- **THEN** the downloader appends the response body to the existing partial file

#### Scenario: Server does not support range requests
- **WHEN** the server responds with `200 OK` (ignoring the Range header)
- **THEN** the downloader overwrites the partial file with the full response

### Requirement: Model acquisition has one process owner
The system SHALL route all model inspection, acquisition, repair, and progress observation through one process-scoped model asset repository. The repository SHALL permit at most one acquisition writer for a model set and SHALL make concurrent callers join the same in-flight result and progress stream.

#### Scenario: Activity and service request the same model set
- **WHEN** the activity-facing bootstrap flow and service initialization request the same model set concurrently
- **THEN** exactly one acquisition operation writes that model set
- **AND** both callers observe the same terminal result

#### Scenario: Request arrives during active acquisition
- **WHEN** a second request for a model set arrives while acquisition is active
- **THEN** the second request joins the active operation
- **AND** it does not truncate, append to, hash, or mark the target independently

#### Scenario: Progress has multiple observers
- **WHEN** multiple bootstrap consumers observe an active acquisition
- **THEN** each consumer receives progress from the single active operation

### Requirement: Acquisition completion requires final full verification
The system SHALL report model acquisition complete only after every required model set passes a fresh full manifest, version, file-presence, nonzero-length, and SHA-256 verification. A successful download call or matching version marker alone SHALL NOT produce a complete result.

#### Scenario: Marker matches but file hash is invalid
- **WHEN** a model set's version marker matches the bundled manifest but any required file hash does not match
- **THEN** the set is considered invalid
- **AND** acquisition repairs or replaces the invalid content
- **AND** completion is withheld until full verification passes

#### Scenario: Download returns without valid assets
- **WHEN** the transfer operation returns but final full verification fails
- **THEN** acquisition reports failure with the verification diagnostic
- **AND** bootstrap does not proceed to native initialization

#### Scenario: All model sets verify after repair
- **WHEN** repaired or downloaded model files and version markers all pass a fresh full verification
- **THEN** the repository reports aggregate model readiness exactly once for that operation

### Requirement: Re-download on version change
The system SHALL acquire or repair a model set when its directory or version marker is absent, when the bundled manifest version differs from the on-disk marker, or when any required file fails presence, nonzero-length, or SHA-256 verification. A completion marker SHALL be written only after every required file in the set has been acquired and verified.

#### Scenario: Version mismatch triggers re-download
- **WHEN** the app checks a model set and its `.talkcan_assets_version` file contains a different version than the bundled manifest
- **THEN** the model set is considered invalid
- **AND** the single acquisition owner re-downloads or repairs the required files

#### Scenario: Version match verifies hashes
- **WHEN** the `.talkcan_assets_version` matches the bundled manifest version
- **THEN** each required model file's presence, nonzero length, and SHA-256 are verified
- **AND** any mismatch marks the model set invalid and requires repair

#### Scenario: Missing marker requires acquisition
- **WHEN** a model directory or completion marker is missing
- **THEN** the model set is considered invalid even if partial files are present
- **AND** resumable acquisition may reuse valid partial bytes according to the existing Range behavior

#### Scenario: Completion marker is committed last
- **WHEN** every required file in a model set has been downloaded and verified
- **THEN** the repository writes the manifest version to the completion marker
- **AND** no matching completion marker is published before all files verify

### Requirement: generateModelHashes Gradle task

The build system SHALL have a `generateModelHashes` Gradle task that produces `model-hashes.json` in `src/main/assets/`.

#### Scenario: Task produces manifest
- **WHEN** `generateModelHashes` runs after the download tasks
- **THEN** it reads the SHA-256 constants from `ParakeetAsset`/`SupertonicAsset` definitions plus the file lists and writes a valid `model-hashes.json` to `src/main/assets/`

#### Scenario: Task is deterministic
- **WHEN** `generateModelHashes` runs twice with the same inputs
- **THEN** it produces byte-identical output
