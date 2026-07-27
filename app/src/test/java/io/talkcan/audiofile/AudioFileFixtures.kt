package io.talkcan.audiofile

import io.talkcan.storage.BackendResult
import io.talkcan.storage.BackendWriteMode
import io.talkcan.storage.DocumentTreeBackend
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.InMemoryDocumentTreeBackend
import io.talkcan.storage.LeaseOwner
import io.talkcan.storage.MountAccessMode
import io.talkcan.storage.MountHandle
import io.talkcan.storage.MountLeaseRegistry
import io.talkcan.storage.MutableResolver
import io.talkcan.storage.MutableRevalidator
import io.talkcan.storage.NodeRef
import io.talkcan.storage.ResolvedMount
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred

// ---------------------------------------------------------------------------
// Outcome helpers
// ---------------------------------------------------------------------------

internal fun <T> AudioFileOutcome<T>.audioSuccess(): T =
    (this as AudioFileOutcome.Success).value

internal fun <T> AudioFileOutcome<T>.audioFailure(): AudioFileError =
    (this as AudioFileOutcome.Failure).error

// ---------------------------------------------------------------------------
// Canonical PCM16 LE WAV builder (test-side mirror of the canonical layout)
// ---------------------------------------------------------------------------

internal fun writeLe16(out: ByteArrayOutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
}

internal fun writeLe32(out: ByteArrayOutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
    out.write((v shr 16) and 0xFF)
    out.write((v shr 24) and 0xFF)
}

/**
 * Builds a canonical RIFF/WAVE PCM document with configurable fields so tests can exercise every
 * strict-decoder branch. [extraChunks] are inserted between `fmt ` and `data` (each RIFF-pad-padded
 * on odd sizes) to prove unknown-chunk skipping. [declaredDataSize] overrides the `data` chunk size
 * field to simulate inconsistent length.
 */
internal fun buildWav(
    sampleRate: Int = 16_000,
    channels: Int = 1,
    bitsPerSample: Int = 16,
    audioFormat: Int = 1,
    blockAlign: Int = channels * (bitsPerSample / 8).coerceAtLeast(1),
    byteRate: Int = sampleRate * blockAlign,
    samples: ShortArray = shortArrayOf(0, 1, -1, 32767, -32768, 2, -2, 3),
    extraChunks: List<Pair<String, ByteArray>> = emptyList(),
    declaredDataSize: Int = samples.size * 2,
): ByteArray {
    val pcm = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = samples[i].toInt()
        pcm[2 * i] = (v and 0xFF).toByte()
        pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
    }
    val extra = ByteArrayOutputStream()
    for ((id, payload) in extraChunks) {
        extra.write(id.toByteArray(Charsets.US_ASCII))
        writeLe32(extra, payload.size)
        extra.write(payload)
        if (payload.size % 2 != 0) extra.write(0) // RIFF odd-size pad byte
    }
    val extraBytes = extra.toByteArray()
    val out = ByteArrayOutputStream()
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    writeLe32(out, 36 + extraBytes.size + pcm.size)
    out.write("WAVE".toByteArray(Charsets.US_ASCII))
    out.write("fmt ".toByteArray(Charsets.US_ASCII))
    writeLe32(out, 16)
    writeLe16(out, audioFormat)
    writeLe16(out, channels)
    writeLe32(out, sampleRate)
    writeLe32(out, byteRate)
    writeLe16(out, blockAlign)
    writeLe16(out, bitsPerSample)
    out.write(extraBytes)
    out.write("data".toByteArray(Charsets.US_ASCII))
    writeLe32(out, declaredDataSize)
    out.write(pcm)
    return out.toByteArray()
}

// ---------------------------------------------------------------------------
// Fake RecordingHost (opaque ownership/quota seam)
// ---------------------------------------------------------------------------

internal class FakeRecordingHost(
    var maxTokens: Int = Int.MAX_VALUE,
    var maxBytes: Long = Long.MAX_VALUE,
) : RecordingHost {
    private data class Entry(val pcm: PcmMonoS16Le, val owner: ExecutionOwner)

    private val entries = LinkedHashMap<String, Entry>()
    private var counter = 0L
    var closed = false

    val admittedTokens = mutableListOf<String>()
    val disposedTokens = mutableListOf<String>()

    fun liveCount(): Int = entries.size
    fun pcmOf(handle: RecordingHandle): PcmMonoS16Le? = entries[handle.token]?.pcm

    override fun admit(pcm: PcmMonoS16Le, owner: ExecutionOwner): RecordingHandle? {
        if (closed) return null
        if (entries.size >= maxTokens) return null
        val total = entries.values.sumOf { it.pcm.pcmBytes } + pcm.pcmBytes
        if (total > maxBytes) return null
        val token = "rec-${counter++}"
        entries[token] = Entry(pcm, owner)
        admittedTokens.add(token)
        return RecordingHandle(token)
    }

    override fun borrow(handle: RecordingHandle, owner: ExecutionOwner): RecordingBorrow {
        if (closed) return RecordingBorrow.Closed
        val entry = entries[handle.token] ?: return RecordingBorrow.Stale
        if (entry.owner.generation != owner.generation) return RecordingBorrow.Stale
        if (entry.owner != owner) return RecordingBorrow.Foreign
        return RecordingBorrow.Borrowed(entry.pcm)
    }

    override fun dispose(handle: RecordingHandle) {
        if (entries.remove(handle.token) != null) disposedTokens.add(handle.token)
    }
}


// ---------------------------------------------------------------------------
// Gated backend (controllable suspension windows for lifecycle tests)
// ---------------------------------------------------------------------------

internal class GatedBackend(val mem: InMemoryDocumentTreeBackend) : DocumentTreeBackend {
    var readGate: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null

    override suspend fun child(parent: NodeRef, name: String): BackendResult<NodeRef> =
        mem.child(parent, name)

    override suspend fun info(node: NodeRef) = mem.info(node)

    override suspend fun createDirectory(parent: NodeRef, name: String) = mem.createDirectory(parent, name)

    override suspend fun listChildren(parent: NodeRef, pageToken: String?, limit: Int) =
        mem.listChildren(parent, pageToken, limit)

    override suspend fun readFile(node: NodeRef, maxBytes: Long): BackendResult<ByteArray> {
        readGate?.await()
        return mem.readFile(node, maxBytes)
    }

    override suspend fun writeFile(parent: NodeRef, name: String, bytes: ByteArray, mode: BackendWriteMode): BackendResult<NodeRef> {
        writeGate?.await()
        return mem.writeFile(parent, name, bytes, mode)
    }

    override suspend fun delete(node: NodeRef) = mem.delete(node)
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

internal class AudioFileHarness(
    val bounds: AudioArtifactBounds = AudioArtifactBounds(),
    val limits: AudioFileLimits = AudioFileLimits(),
    val generation: Long = 1L,
    val recordings: FakeRecordingHost = FakeRecordingHost(),
) {
    val mem = InMemoryDocumentTreeBackend()
    val gated = GatedBackend(mem)

    private val resolved = ResolvedMount(
        mountToken = "output-token",
        declarationId = "output",
        generation = generation,
        access = MountAccessMode.READ_WRITE,
        grantFingerprint = "grant-fingerprint-output",
        backend = gated,
        root = mem.rootRef,
    )
    val resolver = MutableResolver(resolved)
    val revalidator = MutableRevalidator()
    val registry = MountLeaseRegistry(LeaseOwner("state-1", "instance-1", generation), resolver, revalidator)
    val adapter = AudioFileAdapter(registry, recordings, bounds, limits)

    val owner = ExecutionOwner("input-1", ExecutionOwnerKind.INPUT, generation)
    val taskOwner = ExecutionOwner("task-1", ExecutionOwnerKind.TASK, generation)

    fun handle(): MountHandle =
        (registry.open("output") as FilesystemOutcome.Success).value

    /** Admits [pcm] under [owner] and returns the live handle (test shortcut). */
    fun recording(pcm: PcmMonoS16Le, owner: ExecutionOwner = this.owner): RecordingHandle =
        recordings.admit(pcm, owner)!!

}
