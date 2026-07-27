package io.talkcan.audiofile

import io.talkcan.storage.MountHandle

/**
 * 6.4: The language-neutral audio-file capability port.
 *
 * The operation surface the host broker dispatches package audio-file calls to. It composes live
 * mounted-storage authority (a generation-owned mount lease resolved through the shared
 * mount-lease registry) with opaque host Recording data/ownership hooks ([RecordingHost]), and owns
 * strict bounded mono PCM WAV decoding, deterministic WAV export, normalization, quotas,
 * cancellation, and cleanup.
 *
 * Every logical path is validated and confined to one mount before any provider access; every
 * failure is a normalized [AudioFileError] in the fixed portable vocabulary, never a thrown
 * exception and never a platform path, URI, document ID, exception, or codec-native diagnostic.
 * Implementations are provider- and language-neutral: the same port serves a Lua adapter today and
 * a future adapter without change. The capability contains no Journal entry, metadata, output-mode,
 * Markdown, recovery, or path-layout logic.
 */
public interface AudioFilePort {
    /**
     * 6.3: Bounded synchronous Recording description. Resolves one live [handle] owned by [owner]
     * and returns portable media metadata only. Performs no decode or I/O and does not consume the
     * Recording. Foreign → [AudioFileErrorCode.E_INVALID_ARGUMENT] before revealing metadata.
     */
    public fun describe(
        handle: RecordingHandle,
        owner: ExecutionOwner,
    ): AudioFileOutcome<AudioMediaMetadata>

    /**
     * 6.5: Reads and validates a bounded complete WAV/PCM document through the mount and admits one
     * opaque Recording owned by [owner] only after complete successful decode and quota admission.
     * V1 admits only [AudioFileFormat.WAV_PCM_S16LE]; malformed, truncated, oversized, unsupported,
     * or quota-exhausted input publishes no Recording and no partial PCM.
     */
    public suspend fun open(
        owner: ExecutionOwner,
        mount: MountHandle,
        path: String,
        options: AudioOpenOptions,
    ): AudioFileOutcome<RecordingHandle>

    /**
     * 6.6: Borrows (does not consume) one live current-execution Recording and publishes it as a
     * complete authorized destination under the writable mount in the exact requested
     * [AudioExportOptions.format] (`wav-pcm-s16le`). Success is returned only once
     * the complete destination is visible.
     */
    public suspend fun export(
        owner: ExecutionOwner,
        recording: RecordingHandle,
        mount: MountHandle,
        path: String,
        options: AudioExportOptions,
    ): AudioFileOutcome<AudioExportResult>

    /**
     * 6.8: Advances the owning generation. Resets the per-generation transfer budget.
     * Mount-lease liveness is observed through the shared registry; Recording generation binding is
     * the [RecordingHost]'s concern.
     */
    public fun advanceGeneration(newGeneration: Long)

    /** 6.8: Closes the capability. Idempotent. */
    public fun close()
}
