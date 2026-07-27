package io.talkcan.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import io.talkcan.R
import io.talkcan.model.ChannelBrowseEntry
import io.talkcan.model.ChannelStatusKind
import io.talkcan.model.projectChannelBrowseEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto Media surface for Talkcan.
 *
 * Binds to [PttForegroundService] to collect the channel browse projection
 * ([PttForegroundService.channelBrowseEntries]) and the active-app state, then
 * derives the Media tree + now-playing metadata from those flows + the live
 * [CarMediaPttState] emitted by [CarMediaStateBus] (design D3 / D10).
 *
 * Surface contract (see `car-media-channel-browse` & `car-contextual-skip-controls`
 * specs):
 *  - `onLoadChildren` enumerates one [MediaItem] per [ChannelBrowseEntry].
 *  - Browse-item select (`onPlayFromMediaId`) calls
 *    [CarPttCommandBus.setActiveChannel] (equivalent to tap-to-activate).
 *  - Legacy `onPlay` (no media id) starts a Telecom capture on the active
 *    channel — preserved by design D2.
 *  - `onSkipToNext`/`onSkipToPrevious` consult [CarSkipDecision.fromState] and
 *    dispatch to the right [CarPttCommandBus] call.
 */
class CarMediaSessionService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private var browserClientCount = 0

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var browseJob: Job? = null
    private var foregroundService: PttForegroundService? = null
    private var latestBrowseEntries: List<ChannelBrowseEntry> = emptyList()

    private var lastKnownState: CarMediaPttState = CarMediaPttState.NotReady
    private var lastActiveChannelName: String? = null
    private var lastPendingCount: Int = 0

    private val drawables: CarNowPlayingDrawables = CarNowPlayingDrawables(
        notReadyResId = R.drawable.car_art_not_ready,
        readyResId = R.drawable.car_art_ready,
        recordingResId = R.drawable.car_art_recording,
        finalizingResId = R.drawable.car_art_finalizing,
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            @Suppress("DEPRECATION")
            val service = (binder as? PttForegroundService.LocalBinder)?.service() ?: return
            foregroundService = service
            startBrowseAndMetadataCollection(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stopBrowseAndMetadataCollection()
            foregroundService = null
        }

        override fun onBindingDied(name: ComponentName?) {
            stopBrowseAndMetadataCollection()
            foregroundService = null
        }

        override fun onNullBinding(name: ComponentName?) {
            // Foreground service binder unavailable; browse tree stays empty
            // until a later reconnect. Existing onPlay path still operates.
        }
    }

    override fun onCreate() {
        super.onCreate()
        val serviceIntent = Intent(this, PttForegroundService::class.java).apply {
            action = PttForegroundService.ACTION_START_MONITORING
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(
            Intent(this, PttForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )

        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(callback)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMetadata(emptyMetadata())
            setPlaybackState(playbackState(PlaybackState.STATE_PAUSED))
            isActive = true
        }
        sessionToken = session.sessionToken
        CarMediaStateBus.setListener { state -> updateSessionState(state) }
    }

    override fun onDestroy() {
        CarPttCommandBus.release()
        if (browserClientCount > 0) {
            browserClientCount = 0
            AndroidAutoPresenceBus.update(false)
        }
        CarMediaStateBus.setListener(null)
        stopBrowseAndMetadataCollection()
        runCatching { unbindService(serviceConnection) }
        foregroundService = null
        session.isActive = false
        session.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        browserClientCount += 1
        AndroidAutoPresenceBus.update(browserClientCount > 0)
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.sendResult(latestBrowseEntries.map { mediaItemFor(it) }.toMutableList())
    }

    private fun startBrowseAndMetadataCollection(service: PttForegroundService) {
        // Initial snapshot is delivered before the first flow emission so AA's
        // first onGetRoot has something to render.
        publishSnapshot(service)
        browseJob?.cancel()
        browseJob = serviceScope.launch {
            service.channelBrowseEntries.collect { entries ->
                latestBrowseEntries = entries
                val activeId = service.appState.value.activeChannelId
                val activeEntry = entries.firstOrNull { it.id == activeId }
                lastActiveChannelName = activeEntry?.name
                lastPendingCount = activeEntry?.pendingCount ?: 0
                notifyChildrenChanged(ROOT_ID)
                rebuildNowPlaying()
            }
        }
    }

    private fun stopBrowseAndMetadataCollection() {
        browseJob?.cancel()
        browseJob = null
        latestBrowseEntries = emptyList()
    }

    private fun publishSnapshot(service: PttForegroundService) {
        val state = service.appState.value
        val entries = projectChannelBrowseEntries(state)
        latestBrowseEntries = entries
        val activeEntry = entries.firstOrNull { it.id == state.activeChannelId }
        lastActiveChannelName = activeEntry?.name
        lastPendingCount = activeEntry?.pendingCount ?: 0
    }

    private fun mediaItemFor(entry: ChannelBrowseEntry): MediaItem {
        val status = entry.recoveryMessage ?: if (entry.playbackPaused) "PLAYBACK PAUSED" else statusKindPill(entry.statusKind)
        val subtitle = appendPending(status, entry.pendingCount)
        return MediaItem(
            MediaDescription.Builder()
                .setMediaId(entry.id)
                .setTitle(entry.name)
                .setSubtitle(subtitle)
                .build(),
            if (entry.isPlayable) MediaItem.FLAG_PLAYABLE else 0,
        )
    }

    private fun statusKindPill(kind: ChannelStatusKind): String = when (kind) {
        ChannelStatusKind.Active -> "ACTIVE"
        ChannelStatusKind.Ready -> "READY"
        ChannelStatusKind.Standby -> "STANDBY"
        ChannelStatusKind.Unavailable -> "UNAVAILABLE"
    }

    private fun updateSessionState(state: CarMediaPttState) {
        lastKnownState = state
        rebuildNowPlaying()
    }

    private fun rebuildNowPlaying() {
        val meta = buildCarNowPlayingMetadata(
            activeChannelName = lastActiveChannelName,
            state = lastKnownState,
            pendingCount = lastPendingCount,
            appArtist = getString(R.string.app_name),
            notReadyFallbackTitle = getString(R.string.car_media_not_ready_title),
            drawables = drawables,
        )
        session.setMetadata(metadata(meta))
        session.setPlaybackState(playbackState(playbackStateCode(lastKnownState)))
    }

    private fun metadata(meta: CarNowPlayingMetadata): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, meta.subtitle)
        renderBitmap(meta.drawableResId)?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        return builder.build()
    }

    private fun emptyMetadata(): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.car_media_not_ready_title))
        .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
        .putString(MediaMetadata.METADATA_KEY_ALBUM, statePillText(CarMediaPttState.NotReady))
        .build()

    private fun renderBitmap(resId: Int): Bitmap? {
        val drawable: Drawable = ContextCompat.getDrawable(this, resId) ?: return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val sizePx = (ART_SIZE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    private fun playbackStateCode(state: CarMediaPttState): Int = when (state) {
        CarMediaPttState.NotReady -> PlaybackState.STATE_ERROR
        CarMediaPttState.Ready -> PlaybackState.STATE_PAUSED
        CarMediaPttState.Recording -> PlaybackState.STATE_PLAYING
        CarMediaPttState.Finalizing -> PlaybackState.STATE_BUFFERING
    }

    private fun playbackState(state: Int, errorMessage: String? = null): PlaybackState {
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
        errorMessage?.let(builder::setErrorMessage)
        return builder.build()
    }

    private fun rejectPlayback(entry: ChannelBrowseEntry?) {
        val message = entry?.recoveryMessage
            ?: "This channel is unavailable. Recover or remove it on your phone."
        lastKnownState = CarMediaPttState.NotReady
        session.setPlaybackState(playbackState(PlaybackState.STATE_ERROR, message))
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            val activeId = foregroundService?.appState?.value?.activeChannelId
            val activeEntry = latestBrowseEntries.firstOrNull { it.id == activeId }
            if (activeEntry != null) {
                CarPttCommandBus.startTelecomCapture()
            } else {
                rejectPlayback(activeEntry)
            }
        }

        override fun onPause() {
            CarPttCommandBus.release()
        }

        override fun onStop() {
            CarPttCommandBus.release()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId.isNullOrEmpty()) {
                onPlay()
                return
            }
            val entry = latestBrowseEntries.firstOrNull { it.id == mediaId }
            if (entry != null) {
                CarPttCommandBus.setActiveChannel(entry.id)
            } else {
                rejectPlayback(entry)
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) {
                onPlay()
                return
            }
            val entry = latestBrowseEntries.firstOrNull {
                it.name.contains(query, ignoreCase = true)
            }
            if (entry != null) {
                CarPttCommandBus.setActiveChannel(entry.id)
            } else {
                rejectPlayback(null)
            }
        }

        override fun onSkipToNext() {
            dispatchSkip(next = true)
        }

        override fun onSkipToPrevious() {
            dispatchSkip(next = false)
        }

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event?.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> onPlay()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (lastKnownState == CarMediaPttState.Recording) {
                        CarPttCommandBus.release()
                    } else {
                        onPlay()
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                -> CarPttCommandBus.release()
                KeyEvent.KEYCODE_MEDIA_NEXT -> dispatchSkip(next = true)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> dispatchSkip(next = false)
            }
            return true
        }
    }

    private fun dispatchSkip(next: Boolean) {
        val (nextAction, prevAction) = CarSkipDecision.fromState(lastKnownState)
        val action = if (next) nextAction else prevAction
        when (action) {
            CarSkipAction.NoOp -> Unit
            CarSkipAction.NextChannel -> CarPttCommandBus.setActiveChannelOffset(+1)
            CarSkipAction.PrevChannel -> CarPttCommandBus.setActiveChannelOffset(-1)
            CarSkipAction.SkipMessage -> CarPttCommandBus.skipCurrentMessage()
            CarSkipAction.ReplayMessage -> CarPttCommandBus.replayLastHeard()
        }
    }

    companion object {
        private const val SESSION_TAG = "TalkcanCarPtt"
        private const val ROOT_ID = "talkcan-car-ptt-root"
        private const val ART_SIZE_DP = 320
    }
}

internal object CarMediaStateBus {
    private var listener: ((CarMediaPttState) -> Unit)? = null
    private var state: CarMediaPttState = CarMediaPttState.NotReady

    fun setListener(listener: ((CarMediaPttState) -> Unit)?) {
        this.listener = listener
        listener?.invoke(state)
    }

    fun update(state: CarMediaPttState) {
        this.state = state
        listener?.invoke(state)
    }
}

internal enum class CarMediaPttState { NotReady, Ready, Recording, Finalizing }

internal object AndroidAutoPresenceBus {
    private var listener: ((Boolean) -> Unit)? = null
    private var connected: Boolean = false

    fun setListener(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
        listener?.invoke(connected)
    }

    fun update(connected: Boolean) {
        if (this.connected == connected) return
        this.connected = connected
        listener?.invoke(connected)
    }

    fun isConnected(): Boolean = connected
}