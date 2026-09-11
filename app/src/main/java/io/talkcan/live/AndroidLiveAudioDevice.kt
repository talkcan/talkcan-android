package io.talkcan.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import io.talkcan.service.HostAudioCoordinator
import io.talkcan.service.HostFullDuplexAdmission
import io.talkcan.service.HostFullDuplexLease
import io.talkcan.service.TalkcanLogger as Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LIVE_SAMPLE_RATE_HZ = 24_000
private const val LIVE_LOG_TAG = "TalkcanLiveAudio"
private const val MIN_NATIVE_BUFFER_BYTES = 8_192
private const val MAX_NATIVE_BUFFER_BYTES = 96_000

/**
 * Full-duplex host audio for one GPT-Live session: concurrent [AudioRecord]
 * (`VOICE_COMMUNICATION`) capture and streaming [AudioTrack] playback, mono
 * signed PCM16 LE at 24 kHz.
 *
 * Opening order is fixed: `RECORD_AUDIO` fast-fail, exclusive
 * [HostAudioCoordinator] reservation, caller-supplied route acquisition,
 * communication-device snapshot, audio focus, then native capture/output
 * bring-up. Any failure releases partial state in reverse and rethrows; the
 * device never falls back to another route and never steals an existing
 * half-duplex lease.
 *
 * Route ownership stays with Main: [acquireRoute]/[releaseRoute] bracket the
 * route for the current input mode (selected RSM for Work, configured car HFP
 * for OnTheRoad, explicit phone speaker for OnAPinch). [acquireRoute] returns
 * false when the selected route is unavailable — notably when a headset is
 * selected but not connected — and this device then fails instead of falling
 * back to the phone. [communicationDevice] reports the selected communication
 * device; it is snapshotted at open, pins output, selects the matching input,
 * and is re-verified on every read/write alongside an [AudioDeviceCallback]:
 * losing the routed device terminates the stream, it never continues on the
 * phone.
 *
 * Audio-focus loss likewise terminates the stream: pending reads/writes fail
 * and the protocol's close path releases everything.
 */
internal class AndroidLiveAudioDevice(
    private val context: Context,
    private val audioManager: AudioManager,
    private val coordinator: HostAudioCoordinator,
    private val communicationDevice: () -> AudioDeviceInfo?,
    private val acquireRoute: suspend () -> Boolean,
    private val releaseRoute: suspend () -> Unit,
) : LiveAudioDevice {

    @SuppressLint("MissingPermission")
    override suspend fun open(): LiveAudioStream {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission denied")
        }
        val lease = when (val admission = coordinator.reserveFullDuplex()) {
            is HostFullDuplexAdmission.Granted -> admission.lease
            HostFullDuplexAdmission.Busy -> throw IllegalStateException("Host audio busy")
            HostFullDuplexAdmission.Closed -> throw IllegalStateException("Host audio closed")
        }

        var routeAcquired = false
        var focusRequest: AudioFocusRequest? = null
        var record: AudioRecord? = null
        var echoCanceler: AcousticEchoCanceler? = null
        var noiseSuppressor: NoiseSuppressor? = null
        var track: AudioTrack? = null
        var stream: AndroidLiveAudioStream? = null
        var registeredCallback: AudioDeviceCallback? = null

        val focusLost = AtomicBoolean(false)
        val routeLost = AtomicBoolean(false)
        val focusTerminator = AtomicReference<() -> Unit>({ focusLost.set(true) })
        val routeTerminator = AtomicReference<() -> Unit>({ routeLost.set(true) })
        val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                focusLost.set(true)
                runCatching { focusTerminator.get()?.invoke() }
            }
        }

        try {
            if (!acquireRoute()) throw IllegalStateException("Audio route unavailable")
            routeAcquired = true

            val requestedDevice = communicationDevice()
            val deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    val requested = requestedDevice ?: return
                    if (removedDevices.any { it.id == requested.id && it.type == requested.type }) {
                        routeLost.set(true)
                        runCatching { routeTerminator.get()?.invoke() }
                    }
                }
            }
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            registeredCallback = deviceCallback

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw IllegalStateException("Audio focus unavailable")
            }
            focusRequest = request
            fun checkAlive() {
                if (focusLost.get()) throw IOException("Audio focus lost")
                if (routeLost.get()) throw IOException("Audio route lost")
            }

            val minRecordBytes = AudioRecord.getMinBufferSize(
                LIVE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val minTrackBytes = AudioTrack.getMinBufferSize(
                LIVE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minRecordBytes <= 0 || minTrackBytes <= 0) {
                throw IllegalStateException("Audio hardware unavailable")
            }
            if (minRecordBytes > MAX_NATIVE_BUFFER_BYTES / 2 ||
                minTrackBytes > MAX_NATIVE_BUFFER_BYTES / 2
            ) {
                throw IllegalStateException("Audio hardware unavailable")
            }
            val recordBufferBytes = maxOf(minRecordBytes * 2, MIN_NATIVE_BUFFER_BYTES)
            val trackBufferBytes = maxOf(minTrackBytes * 2, MIN_NATIVE_BUFFER_BYTES)

            val openedRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(LIVE_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(recordBufferBytes)
                .build()
            record = openedRecord
            if (openedRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Audio capture unavailable")
            }
            try {
                openedRecord.startRecording()
            } catch (error: IllegalStateException) {
                throw IOException("Audio capture unavailable", error)
            }
            if (openedRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Audio capture unavailable")
            }
            val inputDevice = findMatchingInputDevice(requestedDevice)
            if (inputDevice != null && !openedRecord.setPreferredDevice(inputDevice)) {
                throw IllegalStateException("Audio input device unavailable")
            }
            checkAlive()

            if (AcousticEchoCanceler.isAvailable()) {
                val effect = runCatching {
                    AcousticEchoCanceler.create(openedRecord.audioSessionId)
                }.getOrNull()
                if (effect != null) {
                    if (runCatching { effect.enabled = true }.isSuccess) {
                        echoCanceler = effect
                    } else {
                        runCatching { effect.release() }
                    }
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                val effect = runCatching {
                    NoiseSuppressor.create(openedRecord.audioSessionId)
                }.getOrNull()
                if (effect != null) {
                    if (runCatching { effect.enabled = true }.isSuccess) {
                        noiseSuppressor = effect
                    } else {
                        runCatching { effect.release() }
                    }
                }
            }
            checkAlive()

            val openedTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(LIVE_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(trackBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
            track = openedTrack
            if (requestedDevice != null) {
                if (!openedTrack.setPreferredDevice(requestedDevice)) {
                    throw IllegalStateException("Audio output device unavailable")
                }
            }
            try {
                openedTrack.play()
            } catch (error: IllegalStateException) {
                throw IOException("Audio playback unavailable", error)
            }
            if (openedTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw IllegalStateException("Audio playback unavailable")
            }
            checkAlive()

            val opened = AndroidLiveAudioStream(
                record = openedRecord,
                track = openedTrack,
                echoCanceler = echoCanceler,
                noiseSuppressor = noiseSuppressor,
                audioManager = audioManager,
                focusRequest = request,
                coordinator = coordinator,
                lease = lease,
                releaseRoute = releaseRoute,
                requestedDevice = requestedDevice,
                routeLost = routeLost,
                deviceCallback = deviceCallback,
            )
            // Ownership transfers to the stream: clear the locals so the
            // failure cleanup below does not release them a second time.
            record = null
            echoCanceler = null
            noiseSuppressor = null
            track = null
            focusRequest = null
            routeAcquired = false
            registeredCallback = null
            stream = opened
            focusTerminator.set { opened.terminateFromFocusLoss() }
            routeTerminator.set { opened.terminateFromRouteLoss() }
            checkAlive()
            runCatching { Log.d(LIVE_LOG_TAG, "LIVE_AUDIO_OPEN sampleRate=$LIVE_SAMPLE_RATE_HZ") }
            return opened
        } catch (cancellation: CancellationException) {
            cleanupAfterFailure(
                lease, routeAcquired, focusRequest, record,
                echoCanceler, noiseSuppressor, track, stream,
                registeredCallback, cancellation,
            )
            throw cancellation
        } catch (error: Throwable) {
            cleanupAfterFailure(
                lease, routeAcquired, focusRequest, record,
                echoCanceler, noiseSuppressor, track, stream,
                registeredCallback, error,
            )
            throw error
        }
    }

    /**
     * Finds the capture-side device matching the snapshotted communication
     * device, or null when capture must rely on the `VOICE_COMMUNICATION`
     * communication-routing proof established by route acquisition.
     *
     * The snapshotted device may be output-only (a sink), which
     * [AudioRecord.setPreferredDevice] rejects — hence the address/type match
     * against enumerable inputs instead of reusing it directly.
     */
    private fun findMatchingInputDevice(requested: AudioDeviceInfo?): AudioDeviceInfo? {
        if (requested == null) return null
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val address = runCatching { requested.address }.getOrNull().orEmpty()
        if (address.isNotBlank()) {
            inputs.firstOrNull { runCatching { it.address }.getOrNull() == address }?.let { return it }
        }
        return when (requested.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            else -> null
        }
    }

    private suspend fun cleanupAfterFailure(
        lease: HostFullDuplexLease,
        routeAcquired: Boolean,
        focusRequest: AudioFocusRequest?,
        record: AudioRecord?,
        echoCanceler: AcousticEchoCanceler?,
        noiseSuppressor: NoiseSuppressor?,
        track: AudioTrack?,
        stream: AndroidLiveAudioStream?,
        deviceCallback: AudioDeviceCallback?,
        cause: Throwable,
    ) = withContext(NonCancellable) {
        if (stream != null) {
            runCatching { stream.close() }
        } else {
            runCatching {
                deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            }
            if (track != null) {
                runCatching {
                    if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
                }
                runCatching { track.release() }
            }
            if (echoCanceler != null) runCatching { echoCanceler.release() }
            if (noiseSuppressor != null) runCatching { noiseSuppressor.release() }
            if (record != null) {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
            if (focusRequest != null) {
                runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            }
            if (routeAcquired) runCatching { releaseRoute() }
            coordinator.releaseFullDuplex(lease)
        }
        runCatching {
            Log.w(LIVE_LOG_TAG, "LIVE_AUDIO_OPEN_FAILED reason=${cause::class.java.simpleName}")
        }
    }
}

/**
 * One opened full-duplex audio stream. [read] (capture) and [write] (playback)
 * run concurrently on distinct native objects, serialized against teardown by
 * [ioMutex]: native calls hold it, and [close] stops the natives (unblocking
 * any in-flight call) before acquiring it to release them, so the route and
 * the host reservation are released only after blocked I/O has exited. All
 * [close] callers share [terminal]: the first performs teardown, the rest
 * await it.
 *
 * Every call re-verifies that the communication device snapshotted at open is
 * still the routed device; losing it (or audio focus) fails I/O and stops the
 * natives so the peer direction unblocks too. The stream never continues on
 * the phone after a route loss.
 */
private class AndroidLiveAudioStream(
    private val record: AudioRecord,
    private val track: AudioTrack,
    private val echoCanceler: AcousticEchoCanceler?,
    private val noiseSuppressor: NoiseSuppressor?,
    private val audioManager: AudioManager,
    private val focusRequest: AudioFocusRequest,
    private val coordinator: HostAudioCoordinator,
    private val lease: HostFullDuplexLease,
    private val releaseRoute: suspend () -> Unit,
    private val requestedDevice: AudioDeviceInfo?,
    private val routeLost: AtomicBoolean,
    private val deviceCallback: AudioDeviceCallback,
) : LiveAudioStream {

    private val closed = AtomicBoolean(false)
    private val audioStopped = AtomicBoolean(false)
    private val terminal = CompletableDeferred<Unit>()
    private val captureMutex = Mutex()
    private val playbackMutex = Mutex()
    private val focusLost = AtomicBoolean(false)

    /** Fails I/O and unblocks it now; full cleanup follows in [close]. */
    fun terminateFromFocusLoss() {
        focusLost.set(true)
        stopAudioNow()
    }

    /** Fails I/O and unblocks it now; full cleanup follows in [close]. */
    fun terminateFromRouteLoss() {
        routeLost.set(true)
        stopAudioNow()
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        if (buffer.isEmpty()) return@withContext 0
        throwIfTerminated()
        verifyRoutedDevice()
        val count = captureMutex.withLock {
            try {
                record.read(buffer, 0, buffer.size)
            } catch (error: IllegalStateException) {
                throw IOException("Live audio closed", error)
            }
        }
        when {
            count > 0 -> count
            routeLost.get() -> throw IOException("Audio route lost")
            focusLost.get() -> throw IOException("Audio focus lost")
            closed.get() -> throw IOException("Live audio closed")
            else -> throw IOException("Audio capture failed: $count")
        }
    }

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        var offset = 0
        while (offset < bytes.size) {
            coroutineContext.ensureActive()
            throwIfTerminated()
            verifyRoutedDevice()
            val written = playbackMutex.withLock {
                try {
                    track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                } catch (error: IllegalStateException) {
                    throw IOException("Live audio closed", error)
                }
            }
            when {
                written > 0 -> offset += written
                routeLost.get() -> throw IOException("Audio route lost")
                focusLost.get() -> throw IOException("Audio focus lost")
                closed.get() -> throw IOException("Live audio closed")
                else -> throw IOException("Audio playback failed: $written")
            }
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed.compareAndSet(false, true)) {
            try {
                stopAudioNow()
                captureMutex.withLock { playbackMutex.withLock { releaseAudioNow() } }
                runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
                runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                runCatching { releaseRoute() }
                coordinator.releaseFullDuplex(lease)
                runCatching { Log.d(LIVE_LOG_TAG, "LIVE_AUDIO_CLOSE") }
            } finally {
                terminal.complete(Unit)
            }
        } else {
            terminal.await()
        }
        Unit
    }

    private fun throwIfTerminated() {
        if (routeLost.get()) throw IOException("Audio route lost")
        if (focusLost.get()) throw IOException("Audio focus lost")
        if (closed.get()) throw IOException("Live audio closed")
    }

    private fun verifyRoutedDevice() {
        val requested = requestedDevice ?: return
        val current = runCatching { audioManager.communicationDevice }.getOrNull()
        if (current == null || current.id != requested.id || current.type != requested.type) {
            routeLost.set(true)
            stopAudioNow()
            throw IOException("Audio route lost")
        }
    }

    private fun stopAudioNow() {
        if (!audioStopped.compareAndSet(false, true)) return
        runCatching { record.stop() }
        runCatching { if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop() }
    }

    private fun releaseAudioNow() {
        runCatching { record.release() }
        runCatching { track.release() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
    }
}
