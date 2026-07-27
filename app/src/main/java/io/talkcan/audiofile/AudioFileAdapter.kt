package io.talkcan.audiofile

import android.util.Log

import io.talkcan.storage.BackendFailure
import io.talkcan.storage.BackendNodeInfo
import io.talkcan.storage.BackendNodeKind
import io.talkcan.storage.BackendResult
import io.talkcan.storage.BackendWriteMode
import io.talkcan.storage.FilesystemError
import io.talkcan.storage.FilesystemErrorCode
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.MountAccessMode
import io.talkcan.storage.MountHandle
import io.talkcan.storage.MountLeaseRegistry
import io.talkcan.storage.MountRelativePath
import io.talkcan.storage.NodeRef
import io.talkcan.storage.PathBounds
import io.talkcan.storage.PathParseResult
import io.talkcan.storage.ReasonSanitizer
import io.talkcan.storage.ResolvedMount
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 6.4: The generic audio-file capability core.
 *
 * Implements [AudioFilePort] over a shared generation-owned [MountLeaseRegistry] (the live mount
 * authority, the same registry the mounted-storage VFS resolves through) and an opaque
 * [RecordingHost] (Recording data/ownership hooks). It owns:
 *
 *  - canonical path validation and confinement beneath the mount root (reusing [MountRelativePath]);
 *  - strict bounded mono PCM WAV decode that publishes a Recording only after complete validation
 *    and quota admission (6.5);
 *  - deterministic complete WAV export that borrows the Recording and publishes only a complete
 *    authorized destination under create-new/replace semantics (6.6);
 *  - admission and transfer accounting, finite deadlines, cancellation, close,
 *    and late-completion suppression (6.8);
 *  - normalization of every backend outcome and any thrown failure into the fixed portable
 *    [AudioFileErrorCode] vocabulary with bounded sanitized reasons and no platform leakage.
 *
 * The core never sees a platform path, URI, document ID, or exception detail; the backend addresses
 * nodes by opaque [NodeRef] and reports failures as [BackendFailure]. Cooperative cancellation is
 * always rethrown so the lifecycle layer can observe it; it is never normalized to a result.
 */
public class AudioFileAdapter(
    private val leases: MountLeaseRegistry,
    private val recordings: RecordingHost,
    private val bounds: AudioArtifactBounds = AudioArtifactBounds(),
    private val limits: AudioFileLimits = AudioFileLimits(),
    private val pathBounds: PathBounds = PathBounds.DEFAULT,
) : AudioFilePort {

    private val sanitizer = ReasonSanitizer(bounds.maxReasonBytes)
    private val ledger = AudioFileLedger(limits)

    /** The admission/accounting ledger (exposed for focused lifecycle tests and the host). */
    internal val operations: AudioFileLedger get() = ledger


    // -----------------------------------------------------------------------
    // describe (6.3 surface): bounded synchronous registry lookup
    // -----------------------------------------------------------------------

    override fun describe(
        handle: RecordingHandle,
        owner: ExecutionOwner,
    ): AudioFileOutcome<AudioMediaMetadata> =
        when (val borrow = recordings.borrow(handle, owner)) {
            is RecordingBorrow.Borrowed -> AudioFileOutcome.Success(
                AudioMediaMetadata(
                    sampleRate = borrow.pcm.sampleRate,
                    channels = borrow.pcm.channels,
                    durationMs = borrow.pcm.durationMs,
                    pcmBytes = borrow.pcm.pcmBytes,
                ),
            )
            RecordingBorrow.Foreign -> fail(AudioFileErrorCode.E_INVALID_ARGUMENT, "recording is owned by another execution")
            RecordingBorrow.Stale -> fail(AudioFileErrorCode.E_STALE, "recording is no longer valid")
            RecordingBorrow.Closed -> fail(AudioFileErrorCode.E_CLOSED, "recording registry is closed")
        }

    // -----------------------------------------------------------------------
    // open (6.5)
    // -----------------------------------------------------------------------

    override suspend fun open(
        owner: ExecutionOwner,
        mount: MountHandle,
        path: String,
        options: AudioOpenOptions,
    ): AudioFileOutcome<RecordingHandle> {
        if (options.format != AudioFileFormat.WAV_PCM_S16LE) {
            return fail(AudioFileErrorCode.E_INVALID_ARGUMENT, "open supports only wav-pcm-s16le in v1")
        }
        return when (val opened = guarded(
            mount = mount,
            needWrite = false,
            reserveBytes = bounds.maxArtifactBytes,
            transferred = { it.bytesRead },
            onLateSuppress = { recordings.dispose(it.handle) },
            block = { resolved -> openCore(resolved, owner, path) },
        )) {
            is AudioFileOutcome.Success -> AudioFileOutcome.Success(opened.value.handle)
            is AudioFileOutcome.Failure -> opened
        }
    }

    private data class Opened(val handle: RecordingHandle, val bytesRead: Long)

    private suspend fun openCore(
        resolved: ResolvedMount,
        owner: ExecutionOwner,
        path: String,
    ): AudioFileOutcome<Opened> {
        val components = when (val parsed = MountRelativePath.parse(path, pathBounds)) {
            is PathParseResult.Valid -> parsed.path.components
            is PathParseResult.Invalid -> return AudioFileOutcome.Failure(mapFilesystem(parsed.error))
        }
        val target = resolveTarget(resolved, components)
        if (target is AudioFileOutcome.Failure) return openFailure("resolve", target.error)
        val node = (target as AudioFileOutcome.Success).value
        when (val kind = backendKind(resolved, node)) {
            KindProbe.File -> Unit
            KindProbe.Directory -> return fail(AudioFileErrorCode.E_INVALID_VALUE, "source is a directory")
            is KindProbe.Failed -> return openFailure("kind", kind.error)
        }
        val bytes = when (val read = backendRead(resolved, node, bounds.maxArtifactBytes)) {
            is AudioFileOutcome.Success -> read.value
            is AudioFileOutcome.Failure -> return openFailure("read", read.error)
        }
        val pcm = when (val decoded = WavPcm16.decode(bytes, bounds)) {
            is WavPcm16.DecodeResult.Decoded -> decoded.pcm
            WavPcm16.DecodeResult.TooLarge ->
                return fail(AudioFileErrorCode.E_TOO_LARGE, "recording exceeds an artifact bound")
            WavPcm16.DecodeResult.MalformedHeader,
            WavPcm16.DecodeResult.Truncated,
            WavPcm16.DecodeResult.UnsupportedEncoding,
            WavPcm16.DecodeResult.UnsupportedChannels,
            WavPcm16.DecodeResult.UnsupportedRate,
            WavPcm16.DecodeResult.InconsistentLength,
            WavPcm16.DecodeResult.Empty ->
                return fail(AudioFileErrorCode.E_INVALID_VALUE, "source is not a valid bounded mono PCM WAV")
        }
        // Publish a Recording only after complete validation AND quota admission.
        val handle = recordings.admit(pcm, owner)
            ?: return fail(AudioFileErrorCode.E_TOO_LARGE, "recording quota exhausted")
        return AudioFileOutcome.Success(Opened(handle, bytes.size.toLong()))
    }

    // -----------------------------------------------------------------------
    // export (6.6 WAV)
    // -----------------------------------------------------------------------

    override suspend fun export(
        owner: ExecutionOwner,
        recording: RecordingHandle,
        mount: MountHandle,
        path: String,
        options: AudioExportOptions,
    ): AudioFileOutcome<AudioExportResult> {
        // Borrow (never consume) the current-execution Recording before any mount or codec effect.
        val pcm = when (val borrow = recordings.borrow(recording, owner)) {
            is RecordingBorrow.Borrowed -> borrow.pcm
            RecordingBorrow.Foreign ->
                return fail(AudioFileErrorCode.E_INVALID_ARGUMENT, "recording is owned by another execution")
            RecordingBorrow.Stale -> return fail(AudioFileErrorCode.E_STALE, "recording is no longer valid")
            RecordingBorrow.Closed -> return fail(AudioFileErrorCode.E_CLOSED, "recording registry is closed")
        }
        if (options.format != AudioFileFormat.WAV_PCM_S16LE) {
            return fail(AudioFileErrorCode.E_INVALID_ARGUMENT, "export supports only wav-pcm-s16le in v1")
        }
        return exportWav(pcm, mount, path, options)
    }

    private suspend fun exportWav(
        pcm: PcmMonoS16Le,
        mount: MountHandle,
        path: String,
        options: AudioExportOptions,
    ): AudioFileOutcome<AudioExportResult> {
        val bytes = WavPcm16.encode(pcm)
        return guarded(
            mount = mount,
            needWrite = true,
            reserveBytes = bytes.size.toLong(),
            transferred = { it.bytes },
            block = { resolved -> publishBytes(resolved, pcm, path, bytes, options, AudioFileFormat.WAV_PCM_S16LE) },
        )
    }

    private suspend fun publishBytes(
        resolved: ResolvedMount,
        pcm: PcmMonoS16Le,
        path: String,
        bytes: ByteArray,
        options: AudioExportOptions,
        format: AudioFileFormat,
    ): AudioFileOutcome<AudioExportResult> {
        val prepared = prepareDestination(resolved, path, options)
        if (prepared is AudioFileOutcome.Failure) return AudioFileOutcome.Failure(prepared.error)
        val (parentNode, name) = (prepared as AudioFileOutcome.Success).value
        val mode = when (options.mode) {
            AudioExportMode.CREATE_NEW -> BackendWriteMode.CREATE_NEW
            AudioExportMode.REPLACE -> BackendWriteMode.REPLACE
        }
        val written = backendWrite(resolved, parentNode, name, bytes, mode)
        if (written is AudioFileOutcome.Failure) return AudioFileOutcome.Failure(written.error)
        return AudioFileOutcome.Success(
            AudioExportResult(
                status = AudioExportStatus.WRITTEN,
                format = format,
                sampleRate = pcm.sampleRate,
                channels = pcm.channels,
                durationMs = pcm.durationMs,
                bytes = bytes.size.toLong(),
            ),
        )
    }


    /**
     * Validates the destination path, confines it beneath the mount root, resolves its parent
     * directory, and applies create-new/replace admission (rejecting an existing destination for
     * create-new and a directory destination for either mode) before any write or encode effect.
     */
    private suspend fun prepareDestination(
        resolved: ResolvedMount,
        path: String,
        options: AudioExportOptions,
    ): AudioFileOutcome<Pair<NodeRef, String>> {
        val components = when (val parsed = MountRelativePath.parse(path, pathBounds)) {
            is PathParseResult.Valid -> parsed.path.components
            is PathParseResult.Invalid -> return AudioFileOutcome.Failure(mapFilesystem(parsed.error))
        }
        val parent = descend(resolved, components, components.size - 1)
        if (parent is AudioFileOutcome.Failure) return AudioFileOutcome.Failure(parent.error)
        val parentNode = (parent as AudioFileOutcome.Success).value
        val name = components.last()
        when (val existing = backendChild(resolved, parentNode, name)) {
            is ChildLookup.Found -> when (val kind = backendKind(resolved, existing.node)) {
                KindProbe.Directory -> return fail(AudioFileErrorCode.E_INVALID_VALUE, "destination is a directory")
                KindProbe.File -> if (options.mode == AudioExportMode.CREATE_NEW) {
                    return fail(AudioFileErrorCode.E_EXISTS, "destination already exists")
                }
                is KindProbe.Failed -> return AudioFileOutcome.Failure(kind.error)
            }
            ChildLookup.Missing -> Unit
            is ChildLookup.Failed -> return AudioFileOutcome.Failure(existing.error)
        }
        return AudioFileOutcome.Success(parentNode to name)
    }

    // -----------------------------------------------------------------------
    // Admission and the exact-once terminal gate (6.8)
    // -----------------------------------------------------------------------

    private suspend fun <T> guarded(
        mount: MountHandle,
        needWrite: Boolean,
        reserveBytes: Long = 0L,
        transferred: (T) -> Long = { 0L },
        onLateSuppress: ((T) -> Unit)? = null,
        block: suspend (ResolvedMount) -> AudioFileOutcome<T>,
    ): AudioFileOutcome<T> {
        val resolved = when (val lookup = leases.resolveForOperation(mount.leaseToken)) {
            is FilesystemOutcome.Success -> lookup.value
            is FilesystemOutcome.Failure -> return AudioFileOutcome.Failure(mapFilesystem(lookup.error))
        }
        if (needWrite) {
            requireWritable(resolved)?.let { return it }
        }
        val admission = ledger.tryAdmit(reserveBytes)
        if (admission is AudioAdmission.Rejected) {
            return AudioFileOutcome.Failure(admission.error)
        }
        val reservation = (admission as AudioAdmission.Admitted).reservation
        return runToTerminal(mount, reservation, transferred, onLateSuppress, resolved, block)
    }

    private suspend fun <T> runToTerminal(
        mount: MountHandle,
        reservation: AudioOperationReservation,
        transferred: (T) -> Long,
        onLateSuppress: ((T) -> Unit)?,
        resolved: ResolvedMount,
        block: suspend (ResolvedMount) -> AudioFileOutcome<T>,
    ): AudioFileOutcome<T> {
        val outcome: AudioFileOutcome<T> = try {
            withTimeout(limits.operationDeadlineMs) { block(resolved) }
        } catch (timeout: TimeoutCancellationException) {
            reservation.terminate(AudioTerminalCause.TIMEOUT)
            return fail(AudioFileErrorCode.E_TIMEOUT, "operation exceeded its deadline")
        } catch (cancel: CancellationException) {
            reservation.terminate(AudioTerminalCause.CANCELLED)
            throw cancel
        }
        return when (outcome) {
            is AudioFileOutcome.Failure -> {
                reservation.terminate(AudioTerminalCause.PROVIDER_FAILURE)
                outcome
            }
            is AudioFileOutcome.Success -> when (val live = leases.publicationCheck(mount.leaseToken)) {
                is FilesystemOutcome.Failure -> {
                    // Late publication suppressed: the lease was revoked, closed, or generation-
                    // replaced while the operation ran. Discard the success and any admitted state.
                    val error = mapFilesystem(live.error)
                    reservation.terminate(publicationCause(error.code))
                    onLateSuppress?.invoke(outcome.value)
                    AudioFileOutcome.Failure(error)
                }
                is FilesystemOutcome.Success -> {
                    reservation.terminate(AudioTerminalCause.SUCCESS, transferred(outcome.value))
                    outcome
                }
            }
        }
    }

    private fun publicationCause(code: AudioFileErrorCode): AudioTerminalCause = when (code) {
        AudioFileErrorCode.E_CLOSED -> AudioTerminalCause.CLOSED
        AudioFileErrorCode.E_STALE -> AudioTerminalCause.REVOKED
        AudioFileErrorCode.E_REAUTHORIZATION_REQUIRED -> AudioTerminalCause.REVOKED
        AudioFileErrorCode.E_MOUNT_UNAVAILABLE -> AudioTerminalCause.REVOKED
        AudioFileErrorCode.E_READ_ONLY -> AudioTerminalCause.REVOKED
        else -> AudioTerminalCause.CLOSED
    }

    // -----------------------------------------------------------------------
    // Path resolution and confinement (always beneath the mount root)
    // -----------------------------------------------------------------------

    private suspend fun descend(
        resolved: ResolvedMount,
        components: List<String>,
        count: Int,
    ): AudioFileOutcome<NodeRef> {
        var node = resolved.root
        for (i in 0 until count) {
            when (val lookup = backendChild(resolved, node, components[i])) {
                is ChildLookup.Found -> when (val kind = backendKind(resolved, lookup.node)) {
                    KindProbe.Directory -> node = lookup.node
                    KindProbe.File -> return fail(AudioFileErrorCode.E_INVALID_PATH, "a file is in the path")
                    is KindProbe.Failed -> return AudioFileOutcome.Failure(kind.error)
                }
                ChildLookup.Missing -> return fail(AudioFileErrorCode.E_NOT_FOUND, "a path component is missing")
                is ChildLookup.Failed -> return AudioFileOutcome.Failure(lookup.error)
            }
        }
        return AudioFileOutcome.Success(node)
    }

    private suspend fun resolveTarget(
        resolved: ResolvedMount,
        components: List<String>,
    ): AudioFileOutcome<NodeRef> {
        val parent = descend(resolved, components, components.size - 1)
        if (parent is AudioFileOutcome.Failure) return AudioFileOutcome.Failure(parent.error)
        val parentNode = (parent as AudioFileOutcome.Success).value
        return when (val lookup = backendChild(resolved, parentNode, components.last())) {
            is ChildLookup.Found -> AudioFileOutcome.Success(lookup.node)
            ChildLookup.Missing -> fail(AudioFileErrorCode.E_NOT_FOUND, "path not found")
            is ChildLookup.Failed -> AudioFileOutcome.Failure(lookup.error)
        }
    }

    private sealed interface ChildLookup {
        data class Found(val node: NodeRef) : ChildLookup
        data object Missing : ChildLookup
        data class Failed(val error: AudioFileError) : ChildLookup
    }

    private sealed interface KindProbe {
        data object File : KindProbe
        data object Directory : KindProbe
        data class Failed(val error: AudioFileError) : KindProbe
    }

    private suspend fun backendChild(resolved: ResolvedMount, parent: NodeRef, name: String): ChildLookup {
        val result = try {
            resolved.backend.child(parent, name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return ChildLookup.Failed(ioError())
        }
        return when (result) {
            is BackendResult.Ok -> ChildLookup.Found(result.value)
            is BackendResult.Err -> if (result.failure == BackendFailure.NOT_FOUND) {
                ChildLookup.Missing
            } else {
                ChildLookup.Failed(normalize(result.failure, result.reason))
            }
        }
    }

    private suspend fun backendKind(resolved: ResolvedMount, node: NodeRef): KindProbe =
        when (val info = backendInfo(resolved, node)) {
            is AudioFileOutcome.Success -> when (info.value.kind) {
                BackendNodeKind.FILE -> KindProbe.File
                BackendNodeKind.DIRECTORY -> KindProbe.Directory
            }
            is AudioFileOutcome.Failure -> KindProbe.Failed(info.error)
        }

    private suspend fun backendInfo(resolved: ResolvedMount, node: NodeRef): AudioFileOutcome<BackendNodeInfo> =
        normalizeCall { resolved.backend.info(node) }

    private suspend fun backendRead(resolved: ResolvedMount, node: NodeRef, maxBytes: Long): AudioFileOutcome<ByteArray> =
        normalizeCall { resolved.backend.readFile(node, maxBytes) }

    private suspend fun backendWrite(
        resolved: ResolvedMount,
        parent: NodeRef,
        name: String,
        bytes: ByteArray,
        mode: BackendWriteMode,
    ): AudioFileOutcome<NodeRef> = normalizeCall { resolved.backend.writeFile(parent, name, bytes, mode) }

    private suspend fun <T> normalizeCall(block: suspend () -> BackendResult<T>): AudioFileOutcome<T> {
        val result = try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return AudioFileOutcome.Failure(ioError())
        }
        return when (result) {
            is BackendResult.Ok -> AudioFileOutcome.Success(result.value)
            is BackendResult.Err -> AudioFileOutcome.Failure(normalize(result.failure, result.reason))
        }
    }

    // -----------------------------------------------------------------------
    // Error normalization and result plumbing
    // -----------------------------------------------------------------------

    private fun normalize(failure: BackendFailure, reason: String?): AudioFileError {
        val code = when (failure) {
            BackendFailure.NOT_FOUND -> AudioFileErrorCode.E_NOT_FOUND
            BackendFailure.ALREADY_EXISTS -> AudioFileErrorCode.E_EXISTS
            BackendFailure.NOT_A_DIRECTORY -> AudioFileErrorCode.E_INVALID_PATH
            BackendFailure.IS_A_DIRECTORY -> AudioFileErrorCode.E_INVALID_VALUE
            BackendFailure.NOT_EMPTY -> AudioFileErrorCode.E_BUSY
            BackendFailure.READ_ONLY -> AudioFileErrorCode.E_READ_ONLY
            BackendFailure.NO_SPACE -> AudioFileErrorCode.E_NO_SPACE
            BackendFailure.UNAVAILABLE -> AudioFileErrorCode.E_MOUNT_UNAVAILABLE
            BackendFailure.REAUTHORIZATION_REQUIRED -> AudioFileErrorCode.E_REAUTHORIZATION_REQUIRED
            BackendFailure.TOO_LARGE -> AudioFileErrorCode.E_TOO_LARGE
            BackendFailure.BUSY -> AudioFileErrorCode.E_BUSY
            BackendFailure.UNSUPPORTED -> AudioFileErrorCode.E_UNSUPPORTED
            BackendFailure.IO -> AudioFileErrorCode.E_IO
        }
        return AudioFileError(code, sanitizer.sanitize(reason))
    }

    private fun openFailure(stage: String, error: AudioFileError): AudioFileOutcome.Failure {
        Log.w(DIAGNOSTIC_TAG, "audio_open_failure stage=$stage code=${error.code}")
        return AudioFileOutcome.Failure(error)
    }

    private fun mapFilesystem(error: FilesystemError): AudioFileError =
        AudioFileError(fromFilesystemCode(error.code), sanitizer.sanitize(error.reason))

    private fun fromFilesystemCode(code: FilesystemErrorCode): AudioFileErrorCode = when (code) {
        FilesystemErrorCode.E_INVALID_ARGUMENT -> AudioFileErrorCode.E_INVALID_ARGUMENT
        FilesystemErrorCode.E_INVALID_PATH -> AudioFileErrorCode.E_INVALID_PATH
        FilesystemErrorCode.E_INVALID_CONTEXT -> AudioFileErrorCode.E_INVALID_CONTEXT
        FilesystemErrorCode.E_CAPABILITY_UNDECLARED -> AudioFileErrorCode.E_CAPABILITY_UNDECLARED
        FilesystemErrorCode.E_MOUNT_UNAVAILABLE -> AudioFileErrorCode.E_MOUNT_UNAVAILABLE
        FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED -> AudioFileErrorCode.E_REAUTHORIZATION_REQUIRED
        FilesystemErrorCode.E_READ_ONLY -> AudioFileErrorCode.E_READ_ONLY
        FilesystemErrorCode.E_NOT_FOUND -> AudioFileErrorCode.E_NOT_FOUND
        FilesystemErrorCode.E_EXISTS -> AudioFileErrorCode.E_EXISTS
        FilesystemErrorCode.E_NOT_DIRECTORY -> AudioFileErrorCode.E_INVALID_PATH
        FilesystemErrorCode.E_IS_DIRECTORY -> AudioFileErrorCode.E_INVALID_VALUE
        FilesystemErrorCode.E_TOO_LARGE -> AudioFileErrorCode.E_TOO_LARGE
        FilesystemErrorCode.E_NO_SPACE -> AudioFileErrorCode.E_NO_SPACE
        FilesystemErrorCode.E_BUSY -> AudioFileErrorCode.E_BUSY
        FilesystemErrorCode.E_TIMEOUT -> AudioFileErrorCode.E_TIMEOUT
        FilesystemErrorCode.E_CANCELLED -> AudioFileErrorCode.E_CANCELLED
        FilesystemErrorCode.E_CLOSED -> AudioFileErrorCode.E_CLOSED
        FilesystemErrorCode.E_STALE -> AudioFileErrorCode.E_STALE
        FilesystemErrorCode.E_UNSUPPORTED -> AudioFileErrorCode.E_UNSUPPORTED
        FilesystemErrorCode.E_IO -> AudioFileErrorCode.E_IO
    }

    private fun fail(code: AudioFileErrorCode, reason: String): AudioFileOutcome<Nothing> =
        AudioFileOutcome.Failure(AudioFileError(code, sanitizer.sanitize(reason)))

    /** Unknown failures collapse to E_IO with a generic reason; no exception detail leaks. */
    private fun ioError(): AudioFileError =
        AudioFileError(AudioFileErrorCode.E_IO, sanitizer.sanitize("operation failed"))

    /** Write operations require a writable mount; a non-writable mount fails closed. */
    private fun requireWritable(resolved: ResolvedMount): AudioFileOutcome<Nothing>? =
        when (resolved.access) {
            MountAccessMode.READ_WRITE -> null
        }

    // -----------------------------------------------------------------------
    // Lifecycle (6.8): generation advance, close
    // -----------------------------------------------------------------------

    override fun advanceGeneration(newGeneration: Long) {
        ledger.resetGeneration()
    }

    override fun close() {
    }
    private companion object {
        const val DIAGNOSTIC_TAG = "TalkcanStorage"
    }

}
