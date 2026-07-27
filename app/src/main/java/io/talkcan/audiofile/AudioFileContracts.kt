package io.talkcan.audiofile

/**
 * 6.4: The exact portable audio-file format token.
 *
 * V1 opens and exports only [WAV_PCM_S16LE]. The [token] is the stable wire string a language
 * adapter maps to and from its options-table `format` value; the generic port operates on the
 * typed enum and never parses the string itself.
 */
public enum class AudioFileFormat(public val token: String) {
    WAV_PCM_S16LE("wav-pcm-s16le"),
    ;

    public companion object {
        /** Resolves an exact `format` token, or null when it is unknown. */
        public fun fromToken(token: String): AudioFileFormat? =
            entries.firstOrNull { it.token == token }
    }
}

/** How an [AudioFilePort.export] admits its destination. Exactly `create-new` or `replace`. */
public enum class AudioExportMode {
    CREATE_NEW,
    REPLACE,
}

/** `open` options: the exact requested source format (V1 admits only `wav-pcm-s16le`). */
public data class AudioOpenOptions(val format: AudioFileFormat)

/** `export` options: the exact destination [format] and admission [mode]. */
public data class AudioExportOptions(
    val format: AudioFileFormat,
    val mode: AudioExportMode = AudioExportMode.CREATE_NEW,
)

/** `export` terminal status. */
public enum class AudioExportStatus {
    WRITTEN,
}

/**
 * 6.4/6.6: Exact `export` success result. Published only once the complete destination is visible
 * under the authorized mount. [channels] is always 1 (mono) in V1; [bytes] is the exact published
 * artifact size.
 */
public data class AudioExportResult(
    val status: AudioExportStatus,
    val format: AudioFileFormat,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val bytes: Long,
)

/**
 * 6.3/6.4: Bounded portable media metadata for one live Recording. Exposes only non-negative
 * scalars; never raw samples, a registry token, a path, or a platform value.
 */
public data class AudioMediaMetadata(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val pcmBytes: Long,
)

/**
 * The fixed portable audio-file failure vocabulary.
 *
 * Every expected audio-file failure carries exactly one of these codes. The set mirrors the
 * mounted-storage vocabulary and adds [E_INVALID_VALUE] (a malformed/unsupported source value)
 * and [E_HOST_FAILURE] (an unexpected codec/host failure). Codes are language-neutral and never
 * encode platform paths, URIs, document IDs, exceptions, codec-native diagnostics, pointers, or
 * provider identity.
 */
public enum class AudioFileErrorCode {
    /** A caller-supplied argument or option was malformed, out of bounds, or foreign. */
    E_INVALID_ARGUMENT,

    /** A supplied value (for example a source document) is structurally invalid or unsupported. */
    E_INVALID_VALUE,

    /** A logical path failed canonical mount-relative validation. */
    E_INVALID_PATH,

    /** The call was made from a context not permitted to perform audio-file I/O. */
    E_INVALID_CONTEXT,

    /** The package did not declare the audio-file capability or the named mount. */
    E_CAPABILITY_UNDECLARED,

    /** The addressed mount is not currently usable. */
    E_MOUNT_UNAVAILABLE,

    /** The mount grant must be reauthorized before I/O can proceed. */
    E_REAUTHORIZATION_REQUIRED,

    /** The mount is read-only and the operation requires write access. */
    E_READ_ONLY,

    /** The addressed node does not exist. */
    E_NOT_FOUND,

    /** A create-new destination already exists. */
    E_EXISTS,

    /** A document, artifact, duration, transfer, or staging budget exceeds a finite bound. */
    E_TOO_LARGE,

    /** The underlying store has no space for the operation. */
    E_NO_SPACE,

    /** The operation or node is busy (slot exhaustion or a non-empty directory). */
    E_BUSY,

    /** The operation exceeded its bounded deadline. */
    E_TIMEOUT,

    /** The operation was explicitly cancelled. */
    E_CANCELLED,

    /** The owning generation, mount lease, recording registry, or staging is closed. */
    E_CLOSED,

    /** A Recording or mount lease is stale, foreign, or no longer valid. */
    E_STALE,

    /** The operation or content is not supported (for example a format this host cannot produce). */
    E_UNSUPPORTED,

    /** An otherwise unclassified I/O failure. */
    E_IO,

    /** An unexpected codec or host failure, normalized without native diagnostic leakage. */
    E_HOST_FAILURE,
}

/**
 * A normalized audio-file failure. [reason] is optional, bounded, language-neutral, and sanitized:
 * it never carries a platform path, URI, document ID, exception, codec-native diagnostic, pointer,
 * or provider identity. Callers MUST NOT parse [reason]; it is diagnostic only.
 */
public data class AudioFileError(
    val code: AudioFileErrorCode,
    val reason: String? = null,
)

/**
 * The terminal result of one admitted audio-file operation: exactly one typed [Success] value or a
 * normalized [Failure]. The operation never throws across the host boundary; cooperative
 * cancellation is the only thing propagated, and only to the lifecycle layer.
 */
public sealed interface AudioFileOutcome<out T> {
    public data class Success<T>(val value: T) : AudioFileOutcome<T>
    public data class Failure(val error: AudioFileError) : AudioFileOutcome<Nothing>
}

/**
 * 6.4/6.5: Finite, host-configured bounds for one audio-file artifact.
 *
 * [maxArtifactBytes] caps the source document read through the mount; [maxPcmBytes] and
 * [maxDurationMs] cap the decoded PCM; [supportedSampleRates] is the closed set of admitted sample
 * rates. These are policy numbers, not Lua compatibility promises; the host may tighten them.
 */
public data class AudioArtifactBounds(
    val maxArtifactBytes: Long = 64L shl 20,
    val maxPcmBytes: Long = 64L shl 20,
    val maxDurationMs: Long = 2L * 60 * 60 * 1000,
    val supportedSampleRates: Set<Int> = setOf(8_000, 16_000, 22_050, 44_100, 48_000),
    val maxReasonBytes: Int = 256,
) {
    init {
        require(maxArtifactBytes > 0) { "maxArtifactBytes must be positive" }
        require(maxPcmBytes > 0) { "maxPcmBytes must be positive" }
        require(maxDurationMs > 0) { "maxDurationMs must be positive" }
        require(supportedSampleRates.isNotEmpty()) { "supportedSampleRates must not be empty" }
        require(maxReasonBytes > 0) { "maxReasonBytes must be positive" }
    }
}
