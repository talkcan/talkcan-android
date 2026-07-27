package io.talkcan.audiofile

/**
 * 6.4: The kind of execution that owns a Recording. Language-neutral; a language adapter maps its
 * own owner identity (input invocation or managed task) onto this. Carries no Lua state, userdata,
 * provider credential, or platform value.
 */
public enum class ExecutionOwnerKind {
    INPUT,
    TASK,
}

/**
 * 6.4: Opaque identity of the one execution that owns a set of Recording values, bound to one
 * runtime [generation]. A Recording admitted under an owner is foreign to every other owner and
 * stale once the generation advances or closes.
 */
public data class ExecutionOwner(
    val id: String,
    val kind: ExecutionOwnerKind,
    val generation: Long,
)

/**
 * 6.4: One decoded or captured mono PCM S16LE recording, as held host-side. This is the borrowed
 * host-owned audio view the capability decodes, encodes, and describes; it never crosses to a
 * language runtime as raw samples. [channels] is always 1 in V1.
 */
public class PcmMonoS16Le(
    public val samples: ShortArray,
    public val sampleRate: Int,
) {
    public val channels: Int get() = 1
    public val pcmBytes: Long get() = samples.size.toLong() * 2L
    public val durationMs: Long
        get() = if (sampleRate > 0) samples.size.toLong() * 1000L / sampleRate.toLong() else 0L

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PcmMonoS16Le && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

/**
 * 6.4: Opaque, unforgeable handle to one host-owned Recording.
 *
 * Carries only a token; the [RecordingHost] maps it to the live PCM, owner, and generation. It is
 * not serializable and [toString] reveals no field. Produced only by [RecordingHost.admit]; passed
 * back to borrow/describe/export but never inspected by a caller.
 */
public class RecordingHandle internal constructor(
    internal val token: String,
) {
    override fun toString(): String = "RecordingHandle"
}

internal fun recordingHandle(token: String): RecordingHandle = RecordingHandle(token)

internal fun RecordingHandle.hostToken(): String = token

/**
 * 6.4/6.10: The result of borrowing a Recording for describe/export/transcription. Borrowing never
 * consumes: a [Borrowed] Recording stays live and usable by its owner after the borrow.
 */
public sealed interface RecordingBorrow {
    /** The live, current-execution Recording's borrowed host-owned PCM. */
    public data class Borrowed(val pcm: PcmMonoS16Le) : RecordingBorrow

    /** The handle is owned by another execution. */
    public data object Foreign : RecordingBorrow

    /** The handle's generation has advanced or the Recording was invalidated. */
    public data object Stale : RecordingBorrow

    /** The recording registry is closed. */
    public data object Closed : RecordingBorrow
}

/**
 * 6.4: The opaque host Recording data/ownership seam.
 *
 * The generic audio-file capability composes this hook with live mount authority; it never touches
 * a concrete audio registry, Lua state, or platform object directly. A host (today the Lua audio
 * registry adapter) implements admission, borrow, and disposal over its own opaque recordings,
 * enforcing quota, execution ownership, and generation binding. Garbage collection MUST NOT
 * perform storage, codec, route, or capability effects through this seam.
 */
public interface RecordingHost {
    /**
     * Borrows the Recording [handle] for [owner] without consuming it. Returns the borrowed PCM, or
     * [RecordingBorrow.Foreign]/[RecordingBorrow.Stale]/[RecordingBorrow.Closed] before any codec or
     * storage work.
     */
    public fun borrow(handle: RecordingHandle, owner: ExecutionOwner): RecordingBorrow

    /**
     * Admits freshly decoded [pcm] as a new Recording owned by [owner] under the current generation
     * and registry quota. Returns an opaque handle on admission, or null when quota is exhausted
     * (the implementation disposes the candidate PCM; no live token is published).
     */
    public fun admit(pcm: PcmMonoS16Le, owner: ExecutionOwner): RecordingHandle?

    /**
     * Disposes the Recording [handle], releasing its quota. Used on execution termination,
     * generation close, and to discard a late open completion that was admitted but never published.
     */
    public fun dispose(handle: RecordingHandle)
}
