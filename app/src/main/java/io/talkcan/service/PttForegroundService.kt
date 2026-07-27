package io.talkcan.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.talkcan.MainActivity
import io.talkcan.lua.PluginLogSink
import io.talkcan.lua.PluginLogSinkImpl
import io.talkcan.lua.LogRecord
import io.talkcan.R
import io.talkcan.audio.AndroidMicCaptureSource
import io.talkcan.audio.AndroidPcmOutput
import io.talkcan.audio.AndroidVoiceCommunicationCaptureSource
import io.talkcan.audio.ROUTE_LOG_TAG
import io.talkcan.audio.CaptureService
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.LocalPcmOutput
import io.talkcan.audio.MediaResponsePlayer
import io.talkcan.audio.MediaResponsePcmOutput
import io.talkcan.audio.ModelAssetRepository
import io.talkcan.audio.ResolvedAudioRoute
import io.talkcan.audio.ScoAudioController
import io.talkcan.audio.StateLossCallback
import io.talkcan.audio.audioModeDebugString
import io.talkcan.audio.routeDebugString
import kotlin.time.Duration.Companion.seconds
import io.talkcan.bluetooth.DeviceScanner
import io.talkcan.bluetooth.SppClient
import io.talkcan.bluetooth.SleepwalkerBleConnection
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.JsonKeymapDatabase
import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.channel.capability.AudioOperationArtifact
import io.talkcan.channel.capability.AudioOperationCapabilityAdapter
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.channel.capability.PlaybackResultFactory
import io.talkcan.channel.capability.RecordingPlaybackResultFactory
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.SpeechSynthesisRequest
import io.talkcan.channel.capability.SpeechVoice
import java.util.concurrent.ConcurrentHashMap
import io.talkcan.channel.TextOutputAvailability
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.model.AppState
import io.talkcan.model.BootstrapState
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelBrowseEntry
import io.talkcan.model.InputMode
import io.talkcan.model.InputModeSelection
import io.talkcan.model.ChannelRepository
import io.talkcan.model.ConnectionState
import io.talkcan.model.DEFAULT_TTS_VOICE_STYLE
import io.talkcan.model.DevicePresence
import io.talkcan.model.HardwareMode
import io.talkcan.model.MonitorState
import io.talkcan.model.PermissionState
import io.talkcan.model.PttSource
import io.talkcan.model.RawButtonEvent
import io.talkcan.model.SppState
import io.talkcan.model.projectChannelBrowseEntries
import io.talkcan.model.orderedChannelIds
import io.talkcan.model.selectChannelByOffset
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileTtlOperation
import io.talkcan.protocol.ButtonParser
import io.talkcan.protocol.ButtonStateMachine
import io.talkcan.telecom.TalkcanPhoneAccountRegistrar
import io.talkcan.telecom.TelecomCarPttCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class PluginLogProjection(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
    val timestampMillis: Long,
)

internal fun LogRecord.toPluginLogProjection(): PluginLogProjection? {
    val mappedLevel = when (level.lowercase()) {
        "debug" -> LogLevel.Debug
        "info" -> LogLevel.Info
        "warn" -> LogLevel.Warn
        "error" -> LogLevel.Error
        else -> return null
    }
    return PluginLogProjection(
        level = mappedLevel,
        tag = "LuaChannel",
        message = message,
        throwable = null,
        timestampMillis = timestampMillis,
    )
}

internal suspend fun forwardNextPluginLog(
    sink: PluginLogSink,
    admit: (PluginLogProjection) -> Boolean,
): Boolean {
    val record = sink.receive() ?: return false
    val projection = record.toPluginLogProjection()
    if (projection == null || !admit(projection)) {
        sink.recordProjectionLoss()
    }
    return true
}

internal fun deriveCarMediaPttState(
    phase: PttAudioSessionManager.SessionPhase?,
    onTheRoadAvailable: Boolean,
): CarMediaPttState = when (phase) {
    PttAudioSessionManager.SessionPhase.PttHeld -> CarMediaPttState.Recording
    PttAudioSessionManager.SessionPhase.TerminalWork -> CarMediaPttState.Finalizing
    null -> if (onTheRoadAvailable) CarMediaPttState.Ready else CarMediaPttState.NotReady
}

internal fun rsmChannelOffset(event: RawButtonEvent): Int? = when (event) {
    RawButtonEvent.VolumeUpClicked -> -1
    RawButtonEvent.VolumeDownClicked -> 1
    else -> null
}

internal fun resolveRsmAnnouncementText(
    key: String,
    catalogue: ChannelCatalogueSnapshot,
): String? {
    if (catalogue.definitions.isEmpty()) return null
    if (key == "sys.menu.channels") return "Channels"

    val selected = key.endsWith(".selected")
    val suffix = if (selected) ".selected" else ".name"
    if (!key.startsWith("chan.") || !key.endsWith(suffix)) return null

    val channelId = key.removePrefix("chan.").removeSuffix(suffix)
    val name = catalogue.definitions.firstOrNull { it.id == channelId }?.name ?: return null
    return if (selected) "$name Selected" else name
}

internal fun shouldRetainMonitoringService(reason: ReconnectBlockReason): Boolean =
    reason != ReconnectBlockReason.MonitoringNotRequested

internal fun shouldStopAfterSerialDisconnect(
    serialDisconnectPending: Boolean,
    monitoringRequested: Boolean,
    hasActivePttSession: Boolean,
): Boolean = serialDisconnectPending && !monitoringRequested && !hasActivePttSession

/**
 * Task 13.8: Process-startup recovery for the shared durable work store.
 * Loads every persisted queue partition (corrupted documents are isolated,
 * never merged), then reconciles prior-process claims under the preserved
 * epochs: a claim with no started effect is safely reclaimed to QUEUED in
 * its original FIFO position, and a started-but-uncommitted claim becomes
 * terminally indeterminate.  A started effect is never replayed.  This must
 * run before any package or runtime composition so persisted claims can
 * never block a fresh receive and fresh actors observe a deterministic
 * queue state.
 */
internal fun recoverDurableWorkAtStartup(
    store: io.talkcan.work.DurableWorkStore,
    atMillis: Long,
): io.talkcan.work.WorkStoreResult<io.talkcan.work.WorkRecoveryPlan> =
    when (val loaded = store.load()) {
        is io.talkcan.work.WorkStoreResult.Failure -> loaded
        is io.talkcan.work.WorkStoreResult.Success -> store.reconcileAfterRestart(atMillis)
    }

/**
 * 3.8/13.4: Channel instances whose committed configuration currently
 * selects one of [mutatedProfileIds].  Channel configuration persists only
 * stable profile IDs as dynamic-choice values, so an instance is affected
 * exactly when its payload carries a string value equal to a mutated ID.
 * The result preserves catalogue order for a deterministic generation
 * replacement sequence.
 */
internal fun channelInstancesSelectingProfiles(
    catalogue: ChannelCatalogueSnapshot,
    mutatedProfileIds: Set<String>,
): List<String> {
    if (mutatedProfileIds.isEmpty()) return emptyList()
    val affected = mutableListOf<String>()
    for (definition in catalogue.definitions) {
        if (jsonReferencesAnyProfile(definition.configPayload.toJsonObject(), mutatedProfileIds)) {
            affected += definition.id
        }
    }
    return affected
}

private fun jsonReferencesAnyProfile(value: Any?, profileIds: Set<String>): Boolean = when (value) {
    is String -> value in profileIds
    is org.json.JSONObject ->
        value.keys().asSequence().any { key -> jsonReferencesAnyProfile(value.opt(key), profileIds) }
    is org.json.JSONArray ->
        (0 until value.length()).any { index -> jsonReferencesAnyProfile(value.opt(index), profileIds) }
    else -> false
}

/**
 * 3.8/13.4: After a committed successful generic profile create/edit/delete,
 * force exactly one fresh runtime generation per affected channel instance
 * through [ChannelRuntimeRegistry.reconcileResourceBinding].  The catalogue
 * definition is never rewritten (configuration persists stable profile
 * IDs), so an ordinary catalogue reconcile would retain the live generation
 * with its stale grant snapshot; the forced per-instance replacement stops
 * predecessor admission and closes the predecessor state before the
 * successor publishes its fresh grant — or unavailability — snapshot.
 * Instances that do not select a mutated profile, including every builtin,
 * keep their live generation.
 */
internal suspend fun reconcileSelectedProfileMutation(
    registry: ChannelRuntimeRegistry,
    catalogue: ChannelCatalogueSnapshot,
    mutatedProfileIds: Set<String>,
) {
    for (instanceId in channelInstancesSelectingProfiles(catalogue, mutatedProfileIds)) {
        registry.reconcileResourceBinding(catalogue, instanceId)
    }
}

class PttForegroundService : Service(), CarPttCommandListener, TelecomCarPttCoordinator.Listener, ChannelRouter {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateProjector = ServiceStateProjector(
        onConnectionUpdated = {
            updateInputMode()
            updateCarMediaState()
            if (::foregroundCoordinator.isInitialized) foregroundCoordinator.syncReadinessRefreshLoop()
        },
        onInputModePublished = ::updateCarMediaState,
    )
    val appState: StateFlow<AppState> get() = stateProjector.state

    /**
     * Process-scoped model asset repository — the single authoritative
     * owner of model inspection, acquisition, repair, and progress.
     */
    private lateinit var modelRepository: ModelAssetRepository

    /**
     * Service-owned bootstrap coordinator. Owns the authoritative bootstrap
     * state that drives loading/setup/recovery/dashboard routing.
     */
    private lateinit var bootstrapCoordinator: BootstrapCoordinator

    /** Bootstrap state observed by the activity to decide the root surface. */
    val bootstrapState: StateFlow<BootstrapState>
        get() = bootstrapCoordinator.state

    /** Model acquisition progress for loading display. */
    val modelAcquisitionProgress: StateFlow<io.talkcan.model.ModelAcquisitionProgress>
        get() = modelRepository.progress

    val isCapturing: StateFlow<Boolean> get() = captureService.isCapturing
    val level: StateFlow<Float> get() = captureService.level

    /**
     * Car-browse projection of the channel list. Derived purely from
     * [appState] via [projectChannelBrowseEntries]; the Android Auto Media
     * service collects this to populate `onLoadChildren` and drive
     * `notifyChildrenChanged` (see design D3).
     * Pending counts are derived from provider-neutral runtime snapshots. The browse surface
     * never receives a provider payload, transcript, credential, or SDK object.
     */
    val channelBrowseEntries: Flow<List<ChannelBrowseEntry>>
        get() = stateProjector.state
            .map { state ->
                projectChannelBrowseEntries(state, state.channels.associate { it.id to it.pendingCount })
            }
            .distinctUntilChanged()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var headsetProxy: BluetoothHeadset? = null
    private val headsetServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as? BluetoothHeadset
                serviceScope.launch { refreshReadiness() }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                serviceScope.launch { refreshReadiness() }
            }
        }
    }
    private lateinit var deferredAudioPlayback: DeferredAudioPlaybackCoordinator
    private lateinit var composedDynamicChoiceResolver:
        io.talkcan.model.DynamicConfigurationChoiceResolver
    val dynamicChoiceResolver
        get() = composedDynamicChoiceResolver
    private lateinit var coreInitializer: ServiceCoreInitializer
    private lateinit var providerRegistry: ChannelImplementationProviderRegistry
    /** Service-owned installed-package coordinator; started after built-in registration. */
    private lateinit var installedPackagesCoordinator: InstalledPackagesCoordinator
    /** Internal facade exposing installed-package state and mutations. */
    private lateinit var installedPackagesFacade: InstalledPackagesFacade
    internal val installedPackages: InstalledPackagesFacade get() = installedPackagesFacade
    /** Service-owned package-management coordinator (source resolution, inspection, trust). */
    private lateinit var packageManagementCoordinator: PackageManagementCoordinator
    val packageManagementState: StateFlow<io.talkcan.service.PackageManagementSummary>
        get() = packageManagementCoordinator.managementState
    /** Service-owned generic profile repository (metadata + protected secrets). */
    private lateinit var profileRepository: io.talkcan.profile.ProfileRepository
    /** Service-owned generic profile-management coordinator (5.1). */
    private lateinit var genericProfileCoordinator: GenericProfileManagementCoordinator
    val genericProfileManagementState: StateFlow<io.talkcan.service.GenericProfileManagementState>
        get() = genericProfileCoordinator.state
    private lateinit var pluginLogSink: PluginLogSink
    private var logSinkWorkerJob: Job? = null
    private val _channelDescriptors = MutableStateFlow<List<ChannelImplementationDescriptor>>(emptyList())
    val channelDescriptors: StateFlow<List<ChannelImplementationDescriptor>> = _channelDescriptors.asStateFlow()
    private lateinit var runtimeInvocationBoundary: RuntimeInvocationBoundary
    private lateinit var runtimeRegistry: ChannelRuntimeRegistry
    /** Process-wide synthetic scope for agent-initiated speech synthesis; allocated once, never generation zero. */
    private val agentPlaybackScope = CapabilityScopeIdentity("agent-playback", RuntimeGeneration.next())
    private lateinit var textOutputService: SleepwalkerTextOutputService
    private lateinit var capabilityHost: ServiceChannelCapabilityHost
    private lateinit var scanner: DeviceScanner
    private lateinit var channelRepository: ChannelRepository
    private lateinit var channelManager: ServiceChannelManager
    private lateinit var mountBindingStore: io.talkcan.resource.MountBindingStore
    private lateinit var safMountAdapter: io.talkcan.mount.saf.SafMountAdapter
    private lateinit var mountSelectionController: io.talkcan.ui.MountSelectionController
    val mountTreePickerBridge = io.talkcan.mount.saf.SafTreePickerBridge()
    private lateinit var audioManager: AudioManager
    private lateinit var sco: ScoAudioController
    private lateinit var pcmOutput: AndroidPcmOutput
    private lateinit var telecomCaptureOutput: AndroidPcmOutput
    private lateinit var captureService: CaptureService
    private lateinit var voiceCommunicationSource: AndroidVoiceCommunicationCaptureSource
    lateinit var sleepwalkerConnection: SleepwalkerBleConnection
    private val keymapDatabase: JsonKeymapDatabase by lazy { JsonKeymapDatabase(resources) }
    private val buttonStateMachine = ButtonStateMachine()

    private lateinit var localOutput: MediaResponsePcmOutput
    private lateinit var micSource: AndroidMicCaptureSource
    private lateinit var telecomRegistrar: TalkcanPhoneAccountRegistrar
    private lateinit var mediaResponsePlayer: MediaResponsePlayer
    private lateinit var carTelecomStarter: CarTelecomStarter
    private lateinit var carHfpConfigurationStore: CarHfpConfigurationStore
    private lateinit var carHfpConfigurationController: CarHfpConfigurationController<BluetoothDevice>
    private lateinit var audioSessionManager: PttAudioSessionManager
    private lateinit var hostAudioCoordinator: HostAudioCoordinator
    private lateinit var playbackRouteResolver: io.talkcan.audio.ModePlaybackRouteResolver
    private lateinit var voiceProfileStore: io.talkcan.voice.VoiceProfileStore
    private lateinit var voiceProfileRepository: io.talkcan.voice.VoiceProfileRepository
    private lateinit var voiceProfileEditorCoordinator: VoiceProfileEditorCoordinator
    private lateinit var channelVoiceResolver: ChannelVoiceResolver
    private lateinit var pttDispatcher: PttDispatcher
    private val inputModeController = InputModeController()
    private var idleTimerJob: Job? = null

    private lateinit var readinessProbe: ReadinessProbe
    private lateinit var serialCoordinator: RsmSerialConnectionCoordinator
    private lateinit var foregroundCoordinator: ForegroundServiceCoordinator
    private lateinit var announcementCoordinator: RsmAnnouncementCoordinator


    @SuppressLint("MissingPermission")
    private fun logAudioRouteSnapshot(event: String) {
        TalkcanLogger.d(ROUTE_LOG_TAG,
        "SNAPSHOT event=$event mode=${inputModeController.mode} selectedBy=${inputModeController.selectedBy} " +
            "availability=${inputModeController.availability} audioMode=${audioManager.mode.audioModeDebugString()} " +
            "current=${audioManager.communicationDevice.routeDebugString()} " +
            "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",)
    }


    inner class LocalBinder : Binder() {
        fun service(): PttForegroundService = this@PttForegroundService
    }
    val repository: ChannelRepository
        get() = channelRepository

    val logEntries: StateFlow<List<LogEntry>>
        get() = TalkcanLogger.entries

    val globalLogLevelFlow: StateFlow<LogLevel>
        get() = TalkcanLogger.globalLevelFlow

    val tagLogLevelsFlow: StateFlow<Map<String, LogLevel>>
        get() = TalkcanLogger.perTagLevelFlow

    fun clearLogs() = TalkcanLogger.clear()

    fun setGlobalLogLevel(level: LogLevel) = TalkcanLogger.setGlobalLevel(level)

    fun setTagLogLevel(tag: String, level: LogLevel) = TalkcanLogger.setTagLevel(tag, level)

    fun clearTagLogLevel(tag: String) = TalkcanLogger.clearTagLevel(tag)

    fun refreshCarHfpConfiguration() {
        if (!::carHfpConfigurationController.isInitialized) return
        val configuration = carHfpConfigurationController.refresh()
        stateProjector.publishCarHfpConfiguration(configuration)
    }

    fun selectCarHfpCandidate(selectionId: String) {
        if (!::carHfpConfigurationController.isInitialized) return
        val configuration = carHfpConfigurationController.select(selectionId)
        stateProjector.publishCarHfpConfiguration(configuration)
    }

    // ---- Voice profile subsystem (tasks 4.1-4.6) ----------------------------

    /** Published voice-profile catalogue: read-only built-ins plus custom records. */
    internal val voiceProfileCatalogue: StateFlow<io.talkcan.voice.VoiceProfileCatalogue>
        get() = voiceProfileRepository.catalogue

    /** Published voice-profile editor state: selection, weights, draft, preview, failure. */
    internal val voiceProfileEditorState: StateFlow<VoiceProfileEditorState>
        get() = voiceProfileEditorCoordinator.state

    /**
     * The one explicit operational-audio preemption boundary. Invoked before operational
     * PTT/capture/channel synthesis/playback starts that can contend with editor preview for
     * the host audio admission or the shared synthesizer. Cancels preview work and releases
     * preview-owned routes; never closes the shared synthesizer, operational playback, or
     * unrelated UI state.
     */
    internal fun preemptPreviewForOperationalAudio() {
        if (::voiceProfileEditorCoordinator.isInitialized) {
            voiceProfileEditorCoordinator.preemptPreviewForOperationalAudio()
        }
    }

    // Scalar-ID/value action boundaries: the UI never passes paths or ONNX objects.

    internal fun selectVoiceProfileSources(profileIds: List<String>, discardLatentEdits: Boolean = false) {
        serviceScope.launch(Dispatchers.IO) {
            voiceProfileEditorCoordinator.selectSources(
                profileIds.map(::VoiceProfileId),
                discardLatentEdits,
            )
        }
    }

    internal fun setVoiceProfileEqualWeights() {
        voiceProfileEditorCoordinator.setEqualWeights()
    }

    internal fun setVoiceProfileManualWeights(rawWeights: List<Double>) {
        voiceProfileEditorCoordinator.setManualWeights(rawWeights)
    }

    internal fun setVoiceProfileRandomWeights(seed: Long) {
        voiceProfileEditorCoordinator.setRandomWeights(seed)
    }

    internal fun applyVoiceProfileOperation(operation: VoiceProfileTtlOperation) {
        voiceProfileEditorCoordinator.applyOperation(operation)
    }

    internal fun undoVoiceProfileOperation() {
        voiceProfileEditorCoordinator.undo()
    }

    internal fun resetVoiceProfileDraft() {
        voiceProfileEditorCoordinator.reset()
    }

    internal fun acknowledgeVoiceProfileFailure() {
        voiceProfileEditorCoordinator.acknowledgeFailure()
    }

    internal fun saveVoiceProfileDraftAsNew(displayName: String) {
        serviceScope.launch(Dispatchers.IO) {
            voiceProfileEditorCoordinator.saveDraftAsNew(displayName)
        }
    }

    internal fun renameVoiceProfile(profileId: String, displayName: String) {
        serviceScope.launch(Dispatchers.IO) {
            voiceProfileEditorCoordinator.renameProfile(VoiceProfileId(profileId), displayName)
        }
    }

    internal fun deleteVoiceProfile(profileId: String) {
        serviceScope.launch(Dispatchers.IO) {
            voiceProfileEditorCoordinator.deleteProfile(VoiceProfileId(profileId))
        }
    }

    /** Completes a SAF import before the Activity closes the bounded input stream. */
    internal suspend fun completeVoiceProfileImport(input: java.io.InputStream, displayName: String) {
        voiceProfileEditorCoordinator.importProfile(input, displayName)
    }

    /** Completes a SAF export before the Activity closes the destination stream. */
    internal suspend fun completeVoiceProfileExport(profileId: String, output: java.io.OutputStream) {
        voiceProfileEditorCoordinator.exportProfile(VoiceProfileId(profileId), output)
    }

    internal fun previewVoiceProfileDraft(text: String) {
        voiceProfileEditorCoordinator.requestPreview(text)
    }

    internal fun cancelVoiceProfilePreview() {
        voiceProfileEditorCoordinator.cancelPreview()
    }

    internal fun exitVoiceProfileEditor() {
        serviceScope.launch {
            voiceProfileEditorCoordinator.exitEditor()
        }
    }

    /**
     * Synchronous validated boundary for host-owned per-channel synthesis voice assignment
     * (catalogue v3 preference). Validates the requested profile against the current voice
     * catalogue before commit; a refused or cancelled assignment leaves the catalogue
     * unchanged. Bypasses provider migration and runtime generation reconciliation.
     */
    internal fun updateChannelSynthesisVoiceProfile(
        channelId: String,
        profileId: String?,
        acknowledgeUnverified: Boolean = false,
    ): ChannelVoicePreferenceMutation =
        channelManager.updateChannelVoicePreference(channelId, profileId, acknowledgeUnverified)

    private fun channelsAssignedToVoiceProfile(profileId: VoiceProfileId): List<io.talkcan.voice.VoiceProfileChannelDependency> =
        channelRepository.catalogueState.value.definitions
            .filter { it.hostPreferences.synthesisVoiceProfileId == profileId }
            .map { io.talkcan.voice.VoiceProfileChannelDependency(it.id, it.name) }


    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        TalkcanLogger.initialize(cacheDir)
        pluginLogSink = PluginLogSinkImpl()
        logSinkWorkerJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && forwardNextPluginLog(pluginLogSink) { projection ->
                TalkcanLogger.tryLogPlugin(
                    level = projection.level,
                    tag = projection.tag,
                    message = projection.message,
                    timestamp = projection.timestampMillis,
                )
            }) {
                // Drain until service shutdown closes the bounded sink.
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            val legacyCache = java.io.File(noBackupFilesDir, "announcement-cache")
            if (legacyCache.exists()) legacyCache.deleteRecursively()
        }
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        scanner = DeviceScanner(applicationContext, bluetoothAdapter)
        readinessProbe = ReadinessProbe(this, scanner, bluetoothAdapter, { headsetProxy })
        carHfpConfigurationStore = SharedPreferencesCarHfpConfigurationStore(applicationContext)
        carHfpConfigurationController = CarHfpConfigurationController(
            store = carHfpConfigurationStore,
            hasBluetoothConnect = { RequiredPermissions.hasBluetoothConnect(this) },
            profileDevicesProvider = { headsetProxy?.connectedDevices },
            targetRsmProvider = ::targetRsm,
            addressOf = { it.address },
            displayNameOf = { it.name },
            isConnected = { device -> headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED },
            log = { message -> TalkcanLogger.d(ROUTE_LOG_TAG, message) },
        )
        bluetoothAdapter?.getProfileProxy(this, headsetServiceListener, BluetoothProfile.HEADSET)
        sleepwalkerConnection = SleepwalkerBleConnection()
        textOutputService = SleepwalkerTextOutputService(
            scope = serviceScope,
            connection = sleepwalkerConnection,
            hid = LowLevelHidImpl(),
            keymapDatabase = keymapDatabase,
            connect = { timeoutMillis ->
                sleepwalkerConnection.ensureConnected(bluetoothAdapter, this@PttForegroundService, timeoutMillis)
            },
        )
        // Task 13.7/13.8: Compose the durable work store and coordinator, and
        // reconcile prior-process state BEFORE any package or runtime
        // construction. Loading preserves the persisted queue epochs; restart
        // reconciliation reclaims no-effect claims to QUEUED in place and marks
        // started-uncommitted work terminally indeterminate, so persisted claims
        // can never block a fresh receive and every fresh actor observes a
        // deterministic FIFO state before providers register or generations
        // construct. The coordinator wakes bounded receives without polling and
        // is shared across all package generations.
        val durableWorkStore = io.talkcan.work.DurableWorkStore(
            java.io.File(noBackupFilesDir, "durable-work"),
        )
        val durableWorkCoordinator = io.talkcan.work.DurableWorkCoordinator(durableWorkStore)
        when (val workRecovery = recoverDurableWorkAtStartup(durableWorkStore, System.currentTimeMillis())) {
            is io.talkcan.work.WorkStoreResult.Failure ->
                TalkcanLogger.w("PttForegroundService", "Durable work startup recovery failed: ${workRecovery.failure}")
            is io.talkcan.work.WorkStoreResult.Success -> Unit
        }
        providerRegistry = ChannelImplementationProviderRegistry()
        _channelDescriptors.value = providerRegistry.descriptors()
        channelRepository = ChannelRepository(applicationContext, providerRegistry)
        stateProjector.publishChannels(emptyList())
        // Service-owned voice profile subsystem (task 4.1): custom profiles live under
        // filesDir/voice-profiles — never inside the hash-verified supertonic-3 model set —
        // and built-ins are virtual read-only records backed by the verified F1-F5/M1-M5
        // style files. The editor coordinator publishes bounded immutable state to the
        // Activity; preview reuses the shared TtsController/Supertonic synthesizer and the
        // current-mode playback route.
        voiceProfileStore = io.talkcan.voice.VoiceProfileStore(
            java.io.File(filesDir, VOICE_PROFILE_STORE_DIR),
        )
        voiceProfileStore.ensureDirectory()
        voiceProfileRepository = io.talkcan.voice.VoiceProfileRepository(
            store = voiceProfileStore,
            builtInDir = java.io.File(filesDir, io.talkcan.audio.ModelVerifier.SUPERTONIC_DIR),
            currentModel = io.talkcan.voice.VoiceProfileCodec.CURRENT_MODEL,
            assignedChannels = ::channelsAssignedToVoiceProfile,
        )
        voiceProfileEditorCoordinator = VoiceProfileEditorCoordinator(
            repository = voiceProfileRepository,
            scope = serviceScope,
            previewCacheFile = java.io.File(cacheDir, VOICE_PROFILE_PREVIEW_CACHE_NAME),
            ttsController = {
                if (::coreInitializer.isInitialized) coreInitializer.ttsController else null
            },
            currentInputMode = { inputModeController.mode },
            previewPlayback = { recording, onRouteAcquired ->
                hostAudioCoordinator.play(
                    recording,
                    kind = HostPlaybackKind.PREVIEW,
                    onAcquired = { route -> onRouteAcquired(route.endpoint) },
                ) {
                    playbackRouteResolver.strategyFor(inputModeController.mode)
                } is HostPlaybackResult.Completed
            },
            previewDefaults = {
                val monitor = stateProjector.snapshot().monitor
                VoiceProfilePreviewSynthesisDefaults(
                    lang = monitor.ttsLang,
                    totalSteps = monitor.ttsTotalSteps,
                    speed = monitor.ttsSpeed,
                    scoRate = SCO_RATE,
                )
            },
            preemptHostPlayback = { hostAudioCoordinator.preemptPreviewPlayback() },
        )
        // Per-request host resolution of the semantic `default` synthesis voice (task 6.4):
        // every request reads the requesting instance's current catalogue assignment, so
        // preference changes affect the next request without generation replacement. An
        // unassigned channel resolves to the verified shipped built-in default; an explicit
        // assignment that is unavailable resolves to typed NOT_CONFIGURED behavior instead
        // of a silent substitution. Profile paths never leave the host side.
        channelVoiceResolver = ChannelVoiceResolver(
            channelCatalogue = { channelRepository.catalogueState.value },
            synthesisStylePath = voiceProfileRepository::synthesisStyleFilePath,
            fallbackProfileId = VoiceProfileId(
                "${io.talkcan.voice.VoiceProfileRepository.BUILTIN_ID_PREFIX}$DEFAULT_TTS_VOICE_STYLE",
            ),
        )

        audioManager = getSystemService(AudioManager::class.java)
        sco = ScoAudioController(
            scope = serviceScope,
            audioManager = audioManager,
            rsmHfpConnected = ::isRsmHfpConnected,
            targetRsmName = ::targetRsmName,
            startTargetRsmHfpAudio = ::startTargetRsmHfpAudio,
            stopTargetRsmHfpAudio = ::stopTargetRsmHfpAudio,
            isTargetRsmHfpAudioConnected = ::isTargetRsmHfpAudioConnected,
        )
        pcmOutput = AndroidPcmOutput(audioManager, sco::selectedCommunicationDevice)
        telecomCaptureOutput = AndroidPcmOutput(audioManager, requireActiveScoCommunicationDevice = false)
        val rawLocalOutput = LocalPcmOutput()
        micSource = AndroidMicCaptureSource()
        captureService = CaptureService(serviceScope)
        voiceCommunicationSource = AndroidVoiceCommunicationCaptureSource()
        telecomRegistrar = TalkcanPhoneAccountRegistrar(this)
        telecomRegistrar.register()
        mediaResponsePlayer = MediaResponsePlayer(audioManager, rawLocalOutput)
        localOutput = MediaResponsePcmOutput(rawLocalOutput, mediaResponsePlayer)
        hostAudioCoordinator = HostAudioCoordinator()
        playbackRouteResolver = io.talkcan.audio.ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = ::targetRsm,
            awaitTelecomCaptureRelease = ::awaitTelecomCaptureReleaseForPlayback,
        )
        audioSessionManager = PttAudioSessionManager(
            scope = serviceScope,
            captureService = captureService,
            channelRouter = this,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onTerminalCompleted = ::onAudioSessionTerminalCompleted,
        )
        deferredAudioPlayback = DeferredAudioPlaybackCoordinator(
            scope = serviceScope,
            selectedChannel = { channelRepository.catalogueState.value.activeChannelId },
            operationIsCurrent = { operation ->
                channelRepository.catalogueState.value.definitions.any { it.id == operation.scope.channelInstanceId }
            },
            audio = object : DeferredAudioPlaybackAudioPort {
                override suspend fun playOperationIfAdmitted(
                    channelInstanceId: String,
                    audio: io.talkcan.channel.capability.OpaqueAudioOperation,
                ): DelayedPlaybackAudioResult {
                    val recording = io.talkcan.channel.capability.recordedPcmOf(audio)
                        ?: return DelayedPlaybackAudioResult.Failed(io.talkcan.model.DelayedPlaybackFailureReason.PLAYBACK_FAILED)
                    preemptPreviewForOperationalAudio()
                    return when (val result = hostAudioCoordinator.play(recording) {
                        playbackRouteResolver.strategyFor(inputModeController.mode)
                    }) {
                        HostPlaybackResult.Completed -> DelayedPlaybackAudioResult.Completed
                        HostPlaybackResult.ExplicitlySkipped -> DelayedPlaybackAudioResult.ExplicitlySkipped
                        HostPlaybackResult.Interrupted -> DelayedPlaybackAudioResult.Interrupted
                        HostPlaybackResult.Busy -> DelayedPlaybackAudioResult.Busy
                        HostPlaybackResult.Closed -> DelayedPlaybackAudioResult.Cancelled
                        is HostPlaybackResult.Unavailable,
                        is HostPlaybackResult.Failed,
                        -> DelayedPlaybackAudioResult.Failed(io.talkcan.model.DelayedPlaybackFailureReason.PLAYBACK_FAILED)
                    }
                }
            },
        )
        channelManager = ServiceChannelManager(
            channelRepository = channelRepository,
            providerRegistry = providerRegistry,
            immediateSelection = {},
            deferredSelection = { deferredAudioPlayback.onChannelSelected(it) },
            newChannelId = { java.util.UUID.randomUUID().toString() },
            log = { message -> TalkcanLogger.d(ROUTE_LOG_TAG, message) },
            onConfigurationCommitted = { channelId ->
                serviceScope.launch {
                    runtimeRegistry.reconcile(channelRepository.catalogueState.value)
                }
            },
            voiceProfileCatalogue = { voiceProfileRepository.currentCatalogue },
        )
        // Shared HTTP transport capability: production owns TLS internally via OkHttpClient
        // default constructor. Admission control and owner binding happen at the actor mediation
        // layer (LuaGenericHttpMediator), not in this factory.
        val sharedHttpTransport = io.talkcan.http.OkHttpGenericHttpTransport()
        val sharedHttpCapability = object : io.talkcan.channel.capability.GenericHttpCapability {
            override suspend fun request(
                request: io.talkcan.http.GenericHttpRequest
            ): io.talkcan.http.GenericHttpResult = sharedHttpTransport.request(request)
        }
        capabilityHost = ServiceChannelCapabilityHost(
            textOutputService = textOutputService,
            transcription = ::transcriptionCapability,
            synthesis = ::synthesisCapability,
            audioOperation = ::audioOperationCapability,
            deferredAudioPlayback = { deferredAudioPlayback },
            networkHttp = { _ -> sharedHttpCapability },
        )
        serviceScope.launch(Dispatchers.Default) {
            _channelDescriptors.value = providerRegistry.descriptors()
        }
        runtimeInvocationBoundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.create(workerCount = 2, queueCapacity = 64),
        )
        runtimeRegistry = ChannelRuntimeRegistry(
            providers = providerRegistry,
            capabilityHost = capabilityHost,
            invocationBoundary = runtimeInvocationBoundary,
            runtimeScope = serviceScope,
            closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onPttSessionCancelRequested = {
                pttDispatcher.cancelAnyActivePttForServiceTeardown(
                    caller = PttCancellationCaller.ChannelRuntimeShutdown,
                    reason = "Channel runtime shutdown",
                )
            }
        )
        // 2.7: Compose the generic SAF mount adapter and selection controller after
        // the runtime registry is live. Successful mount binding replacements trigger
        // a single-instance atomic reconcile through reconcileResourceBinding.
        mountBindingStore = io.talkcan.resource.MountBindingStore(
            java.io.File(filesDir, "mount-bindings.json"),
        )
        mountBindingStore.load()
        val safGrantController = io.talkcan.mount.saf.AndroidSafGrantController(contentResolver)
        safMountAdapter = io.talkcan.mount.saf.SafMountAdapter(mountBindingStore, safGrantController)
        mountSelectionController = io.talkcan.ui.MountSelectionController(safMountAdapter) { request, _ ->
            serviceScope.launch {
                runtimeRegistry.reconcileResourceBinding(channelRepository.catalogueState.value, request.ownerInstanceId)
            }
        }
        // Compose the generic profile subsystem BEFORE the installed-package
        // coordinator so the profileRecordProvider callback is available for
        // runtime construction grants (task 13.4/13.7).
        val genericSecretStore = io.talkcan.secret.AndroidKeystoreProtectedSecretStore(
            getSharedPreferences("generic-profile-secrets", android.content.Context.MODE_PRIVATE),
            "talkcan.profile.secret.v1",
        )
        profileRepository = io.talkcan.profile.ProfileRepository(
            io.talkcan.profile.ProfileMetadataStore(java.io.File(noBackupFilesDir, "generic-profiles")),
            genericSecretStore,
        )
        profileRepository.load()
        // Task 13.7/13.8: durableWorkStore and durableWorkCoordinator are
        // composed above, before provider/runtime construction, so startup
        // recovery runs before any package generation can submit, claim, or
        // receive work.
        // Task 10.7/13.7: Compose the resolver orchestrator and source registry.
        // The orchestrator creates one-shot restricted resolver states; the
        // registry routes package-resolver sources to cached factories.
        val luaBridge = io.talkcan.lua.kernel.KotlinLuaKernelBridge()
        val resolverOrchestrator = io.talkcan.lua.resolver.PackageResolverOrchestrator(
            bridge = luaBridge,
            kernelConfig = io.talkcan.lua.LuaKernelConfig(
                hookInterval = 1000,
                instructionBudget = 500_000,
            ),
            secretStore = genericSecretStore,
            httpTransportFactory = { sharedHttpTransport },
            profileProvider = { profileId ->
                runCatching { profileRepository.profile(io.talkcan.profile.ProfileId(profileId)) }
                    .getOrNull()
            },
        )
        val packageResolverSourceRegistry = io.talkcan.model.DynamicConfigurationChoiceSourceRegistry()
        val genericProfileChoiceResolver = GenericProfileDynamicChoiceResolver(profileRepository)
        val keyboardChoices = KeyboardOutputChoiceHierarchy { keymapDatabase.profiles }
        val hostDynamicChoiceResolver = io.talkcan.model.DynamicConfigurationChoiceSourceRegistry().apply {
            register(
                io.talkcan.model.DynamicConfigurationChoiceSourceId(
                    io.talkcan.dependency.DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS
                ),
                5.seconds,
            ) { _ ->
                keyboardChoices.resolvePlatforms()
            }
            register(
                io.talkcan.model.DynamicConfigurationChoiceSourceId(
                    io.talkcan.dependency.DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS
                ),
                5.seconds,
            ) { request ->
                keyboardChoices.resolveLayouts(request)
            }
            register(
                io.talkcan.model.DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
                5.seconds,
            ) { request ->
                keyboardChoices.resolveProfiles(request)
            }
        }
        // Route package-resolver and same-repository profile sources through
        // their generic registries; all host sources retain existing behavior.
        composedDynamicChoiceResolver =
            object : io.talkcan.model.DynamicConfigurationChoiceResolver {
                override suspend fun resolve(
                    request: io.talkcan.model.DynamicConfigurationChoiceRequest,
                ): io.talkcan.model.DynamicConfigurationChoiceResolution {
                    return when (request.effectiveSourceKind) {
                        is io.talkcan.model.DynamicChoiceSourceKind.PackageResolver ->
                            packageResolverSourceRegistry.resolve(request)
                        is io.talkcan.model.DynamicChoiceSourceKind.ProfileType ->
                            genericProfileChoiceResolver.resolve(request)
                        is io.talkcan.model.DynamicChoiceSourceKind.Host ->
                            hostDynamicChoiceResolver.resolve(request)
                    }
                }
            }
        // Compose the service-owned installed-package coordinator after synchronous
        // built-in registration and runtime-registry construction. Package loading runs
        // asynchronously on Dispatchers.IO; built-in providers and foreground-service
        // startup proceed without waiting for package I/O. Lua remains dormant until the
        // runtime registry constructs a generation for a matching catalogue instance.
        installedPackagesCoordinator = InstalledPackagesCoordinator(
            storeRoot = java.io.File(noBackupFilesDir, "installed-lua-packages"),
            providerRegistry = providerRegistry,
            bridge = luaBridge,
            logSink = pluginLogSink,
            runtimeResourcesFactory =
                io.talkcan.lua.AndroidLuaRuntimeResourcesFactory(
                    contentResolver = contentResolver,
                    bindings = mountBindingStore,
                    grants = safGrantController,
                ),
            preparerRegistry =
                io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            dynamicChoiceResolver = composedDynamicChoiceResolver,
            keyboardOutputAdapterFactory = textOutputService::keyboardOutputAdapter,
            secretStore = genericSecretStore,
            httpTransport = sharedHttpTransport,
            workStore = durableWorkStore,
            workCoordinator = durableWorkCoordinator,
            profileRecordProvider = { profileId ->
                runCatching { profileRepository.profile(io.talkcan.profile.ProfileId(profileId)) }
                    .getOrNull()
            },
            dynamicChoiceSourceRegistry = packageResolverSourceRegistry,
            resolverOrchestrator = resolverOrchestrator,
            onCatalogueReconcile = {
                _channelDescriptors.value = providerRegistry.descriptors()
                runtimeRegistry.reconcile(channelRepository.catalogueState.value)
            },
            serviceScope = serviceScope,
        )
        installedPackagesFacade = InstalledPackagesFacade(installedPackagesCoordinator)
        installedPackagesCoordinator.start()
        // Compose the package-management coordinator after the installed-package
        // facade is live. It owns source resolution, candidate inspection, trust
        // confirmation, and delegates committed mutations to the facade. All network
        // and disk I/O runs on Dispatchers.IO; startup is non-blocking.
        val packageStoreRoot = java.io.File(noBackupFilesDir, "installed-lua-packages")
        val gitHubHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val gitHubSourceClient = io.talkcan.dependency.RealGitHubPackageSourceClient(
            io.talkcan.dependency.OkHttpGitHubTransport(gitHubHttpClient),
        )
        packageManagementCoordinator = PackageManagementCoordinator(
            facade = installedPackagesFacade,
            sourceClient = gitHubSourceClient,
            providerRegistry = providerRegistry,
            storeRoot = packageStoreRoot,
            serviceScope = serviceScope,
        )
        packageManagementCoordinator.start()
        genericProfileCoordinator = GenericProfileManagementCoordinator(
            packages = installedPackagesFacade,
            profileRepository = profileRepository,
            serviceScope = serviceScope,
        )
        carTelecomStarter = CarTelecomStarter(
            context = this,
            serviceScope = serviceScope,
            sco = sco,
            audioManager = audioManager,
            headsetProxyProvider = { headsetProxy },
            targetRsm = ::targetRsm,
            inputModeController = inputModeController,
            carConfigurationStore = carHfpConfigurationStore,
            telecomRegistrar = telecomRegistrar,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            publishInputMode = ::publishInputMode,
            isActivePttSession = { pttDispatcher.activePttSession != null },
            decidePttDispatch = { decidePttDispatch(runtimeRegistry.runtimeSnapshots.value) },
            reserveCaptureAdmission = { pttDispatcher.reserveCaptureAdmission() },
            abandonCaptureAdmission = { lease -> pttDispatcher.abandonCaptureAdmission(lease) },
            reservePendingCarPtt = { channelId, lease ->
                pttDispatcher.reservePendingPtt(PttSource.CarTelecom, channelId, lease)
            },
            cancelPendingCarPtt = { reason ->
                pttDispatcher.cancelPttBySource(
                    source = PttSource.CarTelecom,
                    caller = PttCancellationCaller.CarSetupFailure,
                    reason = reason,
                    eligibility = PttAudioSessionManager.CancellationEligibility.PendingOnly,
                )
            },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
            updateCarMediaState = ::updateCarMediaState,
        )
        pttDispatcher = PttDispatcher(
            serviceScope = serviceScope,
            inputModeController = inputModeController,
            audioSessionManager = audioSessionManager,
            audioCoordinator = hostAudioCoordinator,
            preemptPreviewForOperationalAudio = ::preemptPreviewForOperationalAudio,
            resolvePttAudioRoute = ::resolvePttAudioRoute,
            publishInputMode = ::publishInputMode,
            cancelIdleTimer = ::cancelIdleTimer,
            decidePttDispatch = { decidePttDispatch(runtimeRegistry.runtimeSnapshots.value) },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
            updateCarMediaState = ::updateCarMediaState,
        )
        serviceScope.launch {
            textOutputService.availability.collect { availability ->
                val monitorState = when (availability) {
                    TextOutputAvailability.Available -> io.talkcan.model.TextOutputTransportState.Connected
                    TextOutputAvailability.Preparing -> io.talkcan.model.TextOutputTransportState.Connecting
                    TextOutputAvailability.Unavailable,
                    TextOutputAvailability.Closed -> io.talkcan.model.TextOutputTransportState.Disconnected
                }
                updateMonitor { it.copy(textOutputTransportState = monitorState) }
                runtimeRegistry.refreshReadiness()
            }
        }
        CarPttCommandBus.setListener(this)
        TelecomCarPttCoordinator.setListener(this)
        serviceScope.launch {
            combine(
                runtimeRegistry.runtimeSnapshots,
                deferredAudioPlayback.pendingCounts,
            ) { aggregate, deferredPending ->
                Pair(aggregate, deferredPending)
            }.collect { (aggregate, deferredPending) ->
                val projected = aggregate.entries.map { snapshot ->
                    val deferred = deferredPending[snapshot.id] ?: 0
                    snapshot.copy(pendingCount = deferred)
                }
                stateProjector.publishChannelRuntime(projected, aggregate.activeChannelId)
                updateCarMediaState()
            }
        }
        serviceScope.launch {
            channelRepository.catalogueState.collect { snapshot ->
                runtimeRegistry.reconcile(snapshot)
            }
        }
        // Initialize the process-scoped model asset repository, core
        // initializer, and bootstrap coordinator. The initializer owns the
        // Kotlin ONNX STT/TTS, journal, and navigation-TTS resources and implements
        // CoreInit for the bootstrap coordinator.
        modelRepository = ModelAssetRepository(this, serviceScope)
        coreInitializer = ServiceCoreInitializer(
            context = applicationContext,
            scope = serviceScope,
            filesDirProvider = { filesDir },
            textOutputService = textOutputService,
            channelCatalogue = { channelRepository.catalogueState.value },
            modelStatusSink = ModelStatusSink { update ->
                updateMonitor {
                    var monitor = it
                    update.sttModelStatus?.let { s -> monitor = monitor.copy(sttModelStatus = s) }
                    update.ttsModelStatus?.let { s -> monitor = monitor.copy(ttsModelStatus = s) }
                    update.ttsStatus?.let { s -> monitor = monitor.copy(ttsStatus = s) }
                    monitor
                }
            },
            navigationStateLoss = StateLossCallback { failure, enginePackage ->
                bootstrapCoordinator.onNavigationVoiceStateLoss(failure, enginePackage)
            },
            hostAudioPlay = { recording ->
                hostAudioCoordinator.play(recording) {
                    playbackRouteResolver.strategyFor(InputMode.Work)
                } is HostPlaybackResult.Completed
            },
        )
        bootstrapCoordinator = BootstrapCoordinator(
            context = this,
            scope = serviceScope,
            modelRepository = modelRepository,
            coreInit = coreInitializer,
            onModelAssetsReady = voiceProfileRepository::reload,

        )
        bootstrapCoordinator.startBootstrap()

        
        updateActiveControllers()

        serviceScope.launch {
            sco.state.collect { state ->
                updateMonitor { it.copy(scoState = state) }
            }
        }

        AndroidAutoPresenceBus.setListener { connected ->
            updateInputMode()
        }

        serialCoordinator = RsmSerialConnectionCoordinator(
            scope = serviceScope,
            adapterProvider = { bluetoothAdapter },
            scanner = RsmSerialScanner { runCatching { scanner.bondedTarget() }.getOrNull() },
            sppFactory = { adapter -> SppClientAdapter(SppClient(adapter, ButtonParser())) },
            elapsedRealtime = { SystemClock.elapsedRealtime() },
            reconnectScheduler = { delayMs, action ->
                val job = serviceScope.launch {
                    if (delayMs > 0) delay(delayMs)
                    action()
                }
                RsmReconnectHandle { job.cancel() }
            },
            prerequisitesProvider = { device -> reconnectPrerequisites(device ?: runCatching { scanner.bondedTarget() }.getOrNull()) },
            onEvent = ::handleSerialCoordinatorEvent,
        )
        foregroundCoordinator = ForegroundServiceCoordinator(
            scope = serviceScope,
            startForeground = ::startForegroundAtEdge,
            stopForeground = ::stopForegroundAtEdge,
            stopSelf = { startId -> if (startId == null) stopSelf() else stopSelf(startId) },
            refreshReadiness = ::refreshReadiness,
            monitoringRequested = { serialCoordinator.monitoringRequested },
            readyForMonitor = { stateProjector.snapshot().readyForMonitor },
            hasActivePttSession = { pttDispatcher.activePttSession != null },
            refreshIntervalMs = READINESS_REFRESH_INTERVAL_MS,
        )
        announcementCoordinator = RsmAnnouncementCoordinator(
            scope = serviceScope,
            catalogue = { channelRepository.catalogueState.value },
            navigationEngine = { coreInitializer.navigationTtsEngine },
            playPcm = { recording ->
                preemptPreviewForOperationalAudio()
                hostAudioCoordinator.play(recording) {
                    playbackRouteResolver.strategyFor(InputMode.Work)
                }
            },
            onSynthesisResult = bootstrapCoordinator::onNavigationSynthesisResult,
        )
        serialCoordinator.connectSerial()
        refreshReadiness()
        updateInputMode()

        updateCarMediaState()
    }
    private fun transcriptionCapability(identity: CapabilityScopeIdentity) =
        coreInitializer.transcriptionCapability(identity)

    private fun synthesisCapability(identity: CapabilityScopeIdentity) =
        coreInitializer.synthesisCapability(
            identity = identity,
            voiceStylePath = { requesting ->
                preemptPreviewForOperationalAudio()
                channelVoiceResolver.resolveStylePath(requesting)
            },
            totalSteps = { stateProjector.snapshot().monitor.ttsTotalSteps },
        )

    private fun audioOperationCapability(identity: CapabilityScopeIdentity) =
        AudioOperationCapabilityAdapter(
            PlaybackResultFactory { samples, generation ->
                AudioOperationArtifact(
                    io.talkcan.audio.TtsAudio.toScoPlayback(samples, SCO_RATE),
                    generation = generation,
                )
            },
            RecordingPlaybackResultFactory { recording, generation ->
                AudioOperationArtifact(recording, generation = generation)
            },
            identity,
        )

    // ---- Binder commands for bootstrap ----

    fun refreshBootstrapPrerequisites() {
        bootstrapCoordinator.refreshPrerequisites()
    }

    fun startModelAcquisition() {
        bootstrapCoordinator.startModelAcquisition()
    }

    fun retryBootstrap() {
        bootstrapCoordinator.retry()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_MONITORING) {
            foregroundCoordinator.onStartCommand(
                monitoringRequested = serialCoordinator.monitoringRequested,
                startId = startId,
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(45_000L) {
                    pttDispatcher.cancelAnyActivePttForServiceTeardown(
                        caller = PttCancellationCaller.ServiceTeardown,
                        reason = "Service teardown",
                    )
                    // Destruction is resumable: it cancels volatile workers but leaves the durable
                    // ledger intact. Replacement/removal remains generation-retirement work.
                    if (::voiceProfileEditorCoordinator.isInitialized) {
                        voiceProfileEditorCoordinator.release()
                    }
                    coreInitializer.shutdown()
                    hostAudioCoordinator.close()
                    deferredAudioPlayback.close()
                    // Shutdown package management first (source resolution, inspection).
                    // It depends on the installed-package facade, which depends on the
                    // repository. Tear down in dependency order.
                    if (::packageManagementCoordinator.isInitialized) {
                        packageManagementCoordinator.shutdown()
                    }
                    if (::genericProfileCoordinator.isInitialized) {
                        genericProfileCoordinator.close()
                    }
                    // Stop package publication and close the repository before runtime
                    // generations are torn down, so no new providers appear during teardown.
                    if (::installedPackagesCoordinator.isInitialized) {
                        installedPackagesCoordinator.shutdown()
                    }
                    if (::pluginLogSink.isInitialized) {
                        pluginLogSink.close()
                    }
                    runtimeRegistry.shutdownAndAwait()
                    withTimeoutOrNull(500L) {
                        logSinkWorkerJob?.join()
                    }
                    textOutputService.close()
                    runtimeInvocationBoundary.close()
                }
            }
        }
        bootstrapCoordinator.cancelAttempt()
        CarPttCommandBus.setListener(null)
        AndroidAutoPresenceBus.setListener(null)
        TelecomCarPttCoordinator.setListener(null)
        TelecomCarPttCoordinator.forceAbort()
        idleTimerJob?.cancel()
        serialCoordinator.shutdown()
        foregroundCoordinator.stopReadinessRefreshLoop()
        serviceScope.cancel()
        foregroundCoordinator.stopForegroundIfNeeded()
        headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        headsetProxy = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun targetRsm(): BluetoothDevice? =
        runCatching { scanner.bondedTarget() }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun targetRsmName(): String? = targetRsm()?.name

    /**
     * Semantic On-the-road playback cannot inspect or claim a car output while Telecom still
     * owns capture. The route resolver calls this after host playback admission and before
     * selecting a physical output.
     */
    private suspend fun awaitTelecomCaptureReleaseForPlayback(): Boolean {
        repeat(160) {
            if (!TelecomCarPttCoordinator.isCaptureActive() &&
                audioManager.mode == android.media.AudioManager.MODE_NORMAL &&
                audioManager.communicationDevice?.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                return true
            }
            delay(25)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun isRsmHfpConnected(): Boolean = readinessProbe.isRsmHfpConnected()

    @SuppressLint("MissingPermission")
    private fun startTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        val connectionState = runCatching { proxy.getConnectionState(rsm) }.getOrDefault(-1)
        TalkcanLogger.d(ROUTE_LOG_TAG,
        "RSM_HFP_START_REQUEST target='${rsm.name}' connectionState=$connectionState " +
            "audioBefore=${runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)} " +
            "current=${audioManager.communicationDevice.routeDebugString()} " +
            "devices=${audioManager.availableCommunicationDevices.routeDebugString()}",)
        return runCatching { proxy.startVoiceRecognition(rsm) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun stopTargetRsmHfpAudio(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching { proxy.stopVoiceRecognition(rsm) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun isTargetRsmHfpAudioConnected(): Boolean {
        val proxy = headsetProxy ?: return false
        val rsm = targetRsm() ?: return false
        return runCatching { proxy.isAudioConnected(rsm) }.getOrDefault(false)
    }



    private fun updateActiveControllers() {
        if (::runtimeRegistry.isInitialized) {
            serviceScope.launch { runtimeRegistry.refreshReadiness() }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshReadiness() {
        val previous = stateProjector.snapshot().connection
        val snapshot = readinessProbe.refresh(previous.devicePresence, serialCoordinator.targetDevice())
        if (snapshot.bondedDevice != null) serialCoordinator.setTargetDevice(snapshot.bondedDevice)
        updateConnection {
            it.copy(
                permissions = snapshot.permissions,
                missingPermissions = snapshot.missingPermissions,
                bluetoothEnabled = snapshot.bluetoothEnabled,
                devicePresence = snapshot.devicePresence,
                headsetAudio = snapshot.headsetAudio,
            )
        }
        refreshCarHfpConfiguration()
        updateInputMode()

        if (::runtimeRegistry.isInitialized) {
            serviceScope.launch { runtimeRegistry.refreshReadiness() }
        }
        serialCoordinator.onReadinessRefreshed()
    }


    @SuppressLint("MissingPermission")
    fun scanForDevice() {
        serviceScope.launch {
            refreshReadiness()
            if (stateProjector.snapshot().connection.permissions != PermissionState.Granted) return@launch
            if (!stateProjector.snapshot().connection.bluetoothEnabled) return@launch

            updateConnection { it.copy(devicePresence = DevicePresence.Scanning) }
            val found = runCatching { scanner.scanForTarget() }.getOrNull()
            serialCoordinator.setTargetDevice(found)
            updateConnection {
                it.copy(
                    devicePresence = when {
                        found == null -> DevicePresence.NotFound
                        found.bondState == BluetoothDevice.BOND_BONDED -> DevicePresence.Bonded
                        else -> DevicePresence.Found
                    },
                )
            }
            refreshReadiness()
        }
    }

    fun pairTarget() {
        serviceScope.launch {
            val device = serialCoordinator.targetDevice() ?: runCatching { scanner.scanForTarget() }.getOrNull()
            if (device == null) {
                updateConnection { it.copy(devicePresence = DevicePresence.NotFound) }
                return@launch
            }

            serialCoordinator.setTargetDevice(device)
            updateConnection { it.copy(devicePresence = DevicePresence.Pairing) }
            val bonded = runCatching { scanner.createBondAndWait(device) }.getOrDefault(false)
            updateConnection {
                it.copy(devicePresence = if (bonded) DevicePresence.Bonded else DevicePresence.PairingFailed)
            }
            refreshReadiness()
        }
    }

    fun connectSerial() {
        serialCoordinator.connectSerial()
        serviceScope.launch { refreshReadiness() }
    }

    fun disconnectSerial() {
        serialCoordinator.disconnectSerial()
    }


    fun createChannel(
        implementationId: io.talkcan.model.ChannelImplementationId,
        name: String,
        payload: io.talkcan.model.OpaqueJsonObject? = null,
    ): io.talkcan.model.ChannelRepositoryMutationResult =
        channelManager.createChannel(implementationId, name, payload)

    internal fun createChannelWithVoice(
        implementationId: io.talkcan.model.ChannelImplementationId,
        name: String,
        payload: io.talkcan.model.OpaqueJsonObject? = null,
        voiceProfileId: String? = null,
        acknowledgeUnverified: Boolean = false,
    ): ChannelVoicePreferenceMutation =
        channelManager.createChannelWithVoice(implementationId, name, payload, voiceProfileId, acknowledgeUnverified)

    fun updateChannelConfiguration(
        channelId: String,
        payload: io.talkcan.model.OpaqueJsonObject,
    ): io.talkcan.model.ChannelRepositoryMutationResult =
        channelManager.updateChannelConfiguration(channelId, payload)

    fun beginMountSelection(
        request: io.talkcan.ui.MountSelectionRequest,
        declaration: io.talkcan.dependency.PackageMountDeclaration,
    ) {
        mountSelectionController.begin(request, declaration)
    }

    fun completeMountSelection(
        outcome: io.talkcan.mount.saf.SafTreePickerOutcome,
    ): io.talkcan.ui.MountSelectionResult =
        mountSelectionController.complete(outcome)

    fun mountEditorEntries(
        channelInstanceId: String,
        implementationId: io.talkcan.model.ChannelImplementationId,
    ): List<io.talkcan.ui.MountEditorEntry> {
        val descriptor = providerRegistry.descriptors().firstOrNull { it.implementationId == implementationId }
            ?: return emptyList()
        return io.talkcan.ui.MountEditorProjection.entries(
            descriptor.resourceDeclarations.mounts,
        ) { declarationId ->
            val declaration = descriptor.resourceDeclarations.mounts.firstOrNull { it.id == declarationId }
                ?: return@entries io.talkcan.resource.MountAvailability.Unavailable(
                    io.talkcan.resource.MountUnavailableReason.Undeclared,
                )
            val binding = mountBindingStore.currentBinding(channelInstanceId, implementationId, declarationId)
            io.talkcan.resource.MountAvailabilityProjection.project(
                implementationId,
                declaration,
                binding,
            )
        }
    }

    fun selectChannel(id: String): Boolean = channelManager.selectChannel(id)

    fun setActiveChannelId(id: String) {
        selectChannel(id)
    }

    fun setActiveChannelOffset(offset: Int) {
        val orderedIds = orderedChannelIds(stateProjector.snapshot())
        val newId = selectChannelByOffset(orderedIds, stateProjector.snapshot().activeChannelId.orEmpty(), offset)
            ?: return
        selectChannel(newId)
    }

    /**
     * **Future wiring**: skip the currently-playing inbound message on the
     * active channel and advance to the queued one (spec
     * `car-contextual-skip-controls` "Next skips the current inbound message
     * while Finalizing"). No inbound backlog tracking exists today
     * (`pending unheard message state` is not yet implemented), so
     * this method no-ops safely pending the message-backlog capability. When
     * that capability ships, this method should: mark the current inbound
     * message Heard, advance the active-channel inbox pointer, and (if no
     * queued message) fall back to `Ready`. The car [CarMediaStateBus] will
     * emit the resulting state and the now-playing card will reflect it.
     */
    fun skipCurrentMessage() {
    }

    /**
     * **Future wiring**: replay the last heard inbound message on the active
     * channel (spec `car-contextual-skip-controls` "Previous replays the last
     * heard message while Finalizing"). No `last-heard message state` exists
     * today; this method no-ops safely until that capability ships.
     */
    fun replayLastHeard() {
    }

    fun startPhonePtt(channelId: String): Boolean {
        if (!selectChannel(channelId)) return false
        TalkcanLogger.d(ROUTE_LOG_TAG, "PHONE_PTT_PRESSED channel=$channelId")
        logAudioRouteSnapshot("phone-ptt-pressed")
        return pttDispatcher.dispatchPttPressed(PttSource.Phone)
    }

    fun phonePttReleased(channelId: String) {
        pttDispatcher.dispatchPttReleased(PttSource.Phone)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Package-management intents — fire-and-forget wrappers that delegate to
    // the service-owned coordinator on serviceScope. The UI never touches
    // OkHttp, files, streams, or repository transactions directly.
    // ──────────────────────────────────────────────────────────────────────

    fun resolvePackageRepository(url: String) {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.resolveRepository(url)
    }

    fun selectPackageRelease(releaseId: String) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.selectRelease(releaseId) }
    }

    fun confirmPackageInstall(acknowledged: Boolean) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.confirmTrustAndInstall(acknowledged) }
    }

    fun rollbackPackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.confirmRollback(repositoryId, confirmed = true) }
    }

    fun removePackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity) {
        if (!::packageManagementCoordinator.isInitialized) return
        serviceScope.launch { packageManagementCoordinator.confirmRemove(repositoryId, confirmed = true) }
    }

    fun cancelPackageInspection() {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.cancelInspection()
    }

    fun refreshPackageManagement(url: String) {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.refresh(url)
    }

    fun cleanupPackageManagementRouteExit() {
        if (!::packageManagementCoordinator.isInitialized) return
        packageManagementCoordinator.cleanupRouteExit()
    }

    // ── Generic profile management (5.1–5.5) ───────────────────────────────

    fun createGenericProfile(
        identity: io.talkcan.profile.ProfileTypeIdentity,
        displayName: String,
        scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
        secrets: Map<String, CharSequence>,
    ) {
        if (!::genericProfileCoordinator.isInitialized) return
        serviceScope.launch {
            val result = genericProfileCoordinator.createProfile(identity, displayName, scalars, secrets)
            if (result is io.talkcan.profile.ProfileOperationResult.Success) {
                reconcileSelectedProfileAfterCommit(result.value.profileId)
            }
        }
    }

    fun updateGenericProfile(
        profileId: io.talkcan.profile.ProfileId,
        displayName: String,
        scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
        secretEdits: Map<String, io.talkcan.profile.SecretEditAction>,
    ) {
        if (!::genericProfileCoordinator.isInitialized) return
        serviceScope.launch {
            val result = genericProfileCoordinator.editProfile(profileId, displayName, scalars, secretEdits)
            if (result is io.talkcan.profile.ProfileOperationResult.Success) {
                reconcileSelectedProfileAfterCommit(result.value.profileId)
            }
        }
    }

    fun deleteGenericProfile(profileId: io.talkcan.profile.ProfileId) {
        if (!::genericProfileCoordinator.isInitialized) return
        serviceScope.launch {
            val result = genericProfileCoordinator.deleteProfile(profileId)
            if (result is io.talkcan.profile.ProfileOperationResult.Success) {
                reconcileSelectedProfileAfterCommit(profileId)
            }
        }
    }

    fun refreshGenericProfiles() {
        if (!::genericProfileCoordinator.isInitialized) return
        genericProfileCoordinator.refresh()
    }

    /**
     * 3.8/13.4: A committed successful generic profile mutation can change —
     * or remove — the detached grant snapshot of every channel generation
     * that selects the profile. Force one fresh generation per affected
     * instance through the runtime registry so the predecessor revokes
     * authority and closes before the successor publishes its fresh grant,
     * or unavailability, snapshot. Channel configuration is never rewritten;
     * the successor construction re-reads the committed profile record.
     */
    private suspend fun reconcileSelectedProfileAfterCommit(profileId: io.talkcan.profile.ProfileId) {
        if (!::runtimeRegistry.isInitialized) return
        reconcileSelectedProfileMutation(
            registry = runtimeRegistry,
            catalogue = channelRepository.catalogueState.value,
            mutatedProfileIds = setOf(profileId.value),
        )
    }

    fun setInputMode(mode: InputMode): Boolean = setInputMode(mode, InputModeSelection.User)

    fun setInputMode(mode: InputMode, by: InputModeSelection): Boolean {
        val changed = inputModeController.setInputMode(mode, by)
        if (changed) {
            publishInputMode()
            if (::deferredAudioPlayback.isInitialized) {
                deferredAudioPlayback.onAudioAvailable()
            }

        }
        return changed
    }

    private fun updateInputMode() {
        val readyForMonitor = stateProjector.snapshot().readyForMonitor
        val aaConnected = AndroidAutoPresenceBus.isConnected()
        inputModeController.updateInputs(readyForMonitor, aaConnected)
        publishInputMode()
        if (::deferredAudioPlayback.isInitialized) {
            deferredAudioPlayback.onAudioAvailable()
        }

    }

    private fun publishInputMode() {
        stateProjector.publishInputMode(
            mode = inputModeController.mode,
            selectedBy = inputModeController.selectedBy,
            availability = inputModeController.availability,
        )
    }


    private fun onAudioSessionTerminalCompleted(
        completion: PttAudioSessionManager.TerminalCompletion,
    ) {
        pttDispatcher.onTerminalCompleted(completion)
        if (completion.mode == InputMode.OnTheRoad) startIdleTimer()
        deferredAudioPlayback.onAudioAvailable()
        updateCarMediaState()
        foregroundCoordinator.onPttTerminalCompleted()
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            idleTimerJob = null
            updateCarMediaState()
        }
    }

    private fun cancelIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }


    override fun onCarPttStart() {
        cancelIdleTimer()
        carTelecomStarter.startTelecomCarPtt()
    }

    override fun onCarPttRelease() {
        TelecomCarPttCoordinator.forceAbort()
    }

    override fun onTelecomCaptureStart() {
        pttDispatcher.dispatchPttPressed(PttSource.CarTelecom)
    }

    override fun onTelecomCaptureStop() {
        pttDispatcher.dispatchPttReleased(PttSource.CarTelecom)
    }
    override fun onTelecomRouteTimeout() {
        val cancellation = pttDispatcher.cancelPttBySource(
            source = PttSource.CarTelecom,
            caller = PttCancellationCaller.TelecomRouteTimeout,
            reason = "Telecom route timeout",
        )
        if (cancellation.disposition == PttAudioSessionManager.CancellationDisposition.Accepted) {
            carTelecomStarter.playCarErrorBeep()
        }
        carTelecomStarter.notifyTelecomDisconnected()
    }
    override fun onTelecomConnectionEnded() {
        pttDispatcher.cancelPttBySource(
            source = PttSource.CarTelecom,
            caller = PttCancellationCaller.TelecomConnectionEnded,
            reason = "Telecom connection ended",
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOnly,
        )
        carTelecomStarter.notifyTelecomDisconnected()
    }

    private fun cycleActiveChannel(offset: Int) {
        val previousId = stateProjector.snapshot().activeChannelId
        setActiveChannelOffset(offset)
        val newId = stateProjector.snapshot().activeChannelId
        if (newId == previousId) {
            announcementCoordinator.announceErrorBeep()
        } else {
            announcementCoordinator.announce("chan.$newId.name")
        }
    }

    override fun onCarSetActiveChannel(id: String) {
        setActiveChannelId(id)
    }

    override fun onCarSetActiveChannelOffset(offset: Int) {
        setActiveChannelOffset(offset)
    }

    override fun onCarSkipMessage() {
        skipCurrentMessage()
    }

    override fun onCarReplayMessage() {
        replayLastHeard()
    }

    private fun handleRawButtonEvent(event: RawButtonEvent) {
        val previousMode = stateProjector.snapshot().monitor.hardwareMode
        val snapshot = buttonStateMachine.apply(event, SystemClock.elapsedRealtime())
        updateMonitor {
            it.copy(
                hardwareMode = snapshot.hardwareMode,
                buttons = snapshot.buttons,
            )
        }

        when (event) {
            RawButtonEvent.GroupPressed -> {
                if (previousMode != HardwareMode.Control && snapshot.hardwareMode == HardwareMode.Control) {
                    announcementCoordinator.announce("sys.menu.channels")
                }
            }
            RawButtonEvent.VolumeUpClicked,
            RawButtonEvent.VolumeDownClicked -> {
                if (snapshot.hardwareMode == HardwareMode.Control) {
                    cycleActiveChannel(checkNotNull(rsmChannelOffset(event)))
                }
                scheduleVolumeExpiry()
            }
            RawButtonEvent.PttPressed -> {
                if (previousMode == HardwareMode.Control) {
                    selectChannel(stateProjector.snapshot().activeChannelId.orEmpty())
                    announcementCoordinator.announce("chan.${stateProjector.snapshot().activeChannelId.orEmpty()}.selected")
                } else {
                    pttDispatcher.dispatchPttPressed(PttSource.Rsm)
                }
            }
            RawButtonEvent.PttReleased -> pttDispatcher.dispatchPttReleased(PttSource.Rsm)
            RawButtonEvent.SosPressed -> serviceScope.launch {
                if (hostAudioCoordinator.consumeSosDuringPlayback() is HostSosDisposition.DispatchToChannel) {
                    runtimeRegistry.dispatchSos(stateProjector.snapshot().activeChannelId.orEmpty())
                }
            }
            else -> Unit
        }
    }



    private fun resolvePttAudioRoute(mode: InputMode): ResolvedAudioRoute =
        io.talkcan.service.resolvePttAudioRoute(
            mode = mode,
            sco = sco,
            telecomCaptureOutput = telecomCaptureOutput,
            mediaResponsePlayer = mediaResponsePlayer,
            voiceCommunicationSource = voiceCommunicationSource,
            localOutput = localOutput,
            micSource = micSource,
            pcmOutput = pcmOutput,
            awaitTelecomDisconnected = { withTimeoutOrNull(POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS) { carTelecomStarter.telecomDisconnected.await() } },
            releaseStaleWorkRoute = { reason -> sco.releaseImmediately(reason) },
            releaseTelecomCaptureRoute = {
                io.talkcan.service.releaseTelecomCaptureRoute(
                    audioManager, ::logAudioRouteSnapshot,
                )
            },
            logAudioRouteSnapshot = ::logAudioRouteSnapshot,
        )

    private fun updateCarMediaState() {
        CarMediaStateBus.update(
            deriveCarMediaPttState(
                phase = pttDispatcher.activePttSession?.phase,
                onTheRoadAvailable = inputModeController.availability.onTheRoad,
            ),
        )
    }




    private fun handleSerialCoordinatorEvent(event: SerialCoordinatorEvent) {
        when (event) {
            is SerialCoordinatorEvent.CancelPtt -> pttDispatcher.cancelPttBySource(
                source = PttSource.Rsm,
                caller = event.caller,
                reason = event.reason,
            )
            SerialCoordinatorEvent.ReleaseTts -> serviceScope.launch {
                coreInitializer.ttsController?.cancelAndRelease()
            }
            is SerialCoordinatorEvent.SppStateChanged -> updateConnection {
                it.copy(
                    spp = event.state,
                    sppError = event.error,
                )
            }
            is SerialCoordinatorEvent.DevicePresenceChanged -> updateConnection {
                it.copy(devicePresence = event.presence)
            }
            SerialCoordinatorEvent.RequestEnsureForeground -> foregroundCoordinator.ensureForeground()
            SerialCoordinatorEvent.RequestStopForegroundAndSelf -> foregroundCoordinator.requestStopForegroundAndSelf()
            SerialCoordinatorEvent.RequestStopReadinessRefreshLoop -> foregroundCoordinator.stopReadinessRefreshLoop()
            SerialCoordinatorEvent.RequestReevaluateSerialDisconnectShutdown -> foregroundCoordinator.reevaluateSerialDisconnectShutdown()
            SerialCoordinatorEvent.RequestReadinessRefresh -> refreshReadiness()
            is SerialCoordinatorEvent.SerialDisconnectPendingChanged -> foregroundCoordinator.onSerialDisconnectPendingChanged(event.pending)
            is SerialCoordinatorEvent.RawButtonReceived -> handleRawButtonEvent(event.event)
            is SerialCoordinatorEvent.LogTermination -> TalkcanLogger.d(
                ROUTE_LOG_TAG,
                "RSM_SPP_SESSION_TERMINATION mode=${if (event.automatic) "Automatic" else "Manual"} " +
                    "everConnected=${event.everConnected} monitoringRequested=${event.monitoringRequested} " +
                    "reconnectDisposition=${event.disposition}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnectPrerequisites(
        bondedTarget: BluetoothDevice?,
    ): ReconnectPrerequisites = ReconnectPrerequisites(
        permissionsGranted = stateProjector.snapshot().connection.permissions == PermissionState.Granted,
        bluetoothEnabled = stateProjector.snapshot().connection.bluetoothEnabled,
        bondedTargetAvailable = bondedTarget?.bondState == BluetoothDevice.BOND_BONDED,
    )

    private fun scheduleVolumeExpiry() {
        serviceScope.launch {
            delay(300)
            val snapshot = buttonStateMachine.expireClicks(SystemClock.elapsedRealtime())
            updateMonitor {
                it.copy(
                    hardwareMode = snapshot.hardwareMode,
                    buttons = snapshot.buttons,
                )
            }
        }
    }

    private fun updateConnection(transform: (ConnectionState) -> ConnectionState) {
        stateProjector.updateConnection(transform)
    }

    private fun updateMonitor(transform: (MonitorState) -> MonitorState) {
        stateProjector.updateMonitor(transform)
    }

    /**
     * Android-edge foreground start. Creates the notification channel and
     * notification, then invokes the actual [android.app.Service.startForeground]
     * with the existing notification ID, content, and foreground-service types.
     * Returns true on success; the coordinator owns the logical foreground flag
     * and readiness-loop sync.
     */
    private fun startForegroundAtEdge(): Boolean {
        createNotificationChannel()
        val notification = buildNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
    }

    /**
     * Android-edge foreground stop. Invokes the actual
     * [android.app.Service.stopForeground] with the existing removal flag. The
     * coordinator owns the logical foreground flag and readiness-loop sync.
     */
    private fun stopForegroundAtEdge() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_talkcan)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }


    // -- ChannelRouter implementation --------------------------------------------------

    override suspend fun prepareInput(channelId: String): ChannelInputAcceptance {
        TalkcanLogger.d(ROUTE_LOG_TAG, "CHANNEL_INPUT_PREPARE channel=$channelId")
        return runtimeRegistry.prepareInput(channelId)
    }
    companion object {
        const val ACTION_START_MONITORING = "io.talkcan.START_MONITORING"

        const val NOTIFICATION_CHANNEL_ID = "talkcan_device_link"
        const val NOTIFICATION_ID = 41

        private const val READINESS_REFRESH_INTERVAL_MS = 5_000L
        private const val SCO_RATE = 16_000
        private const val VOICE_PROFILE_STORE_DIR = "voice-profiles"
        private const val VOICE_PROFILE_PREVIEW_CACHE_NAME = "voice-profile-preview.json"
        private const val POST_TELECOM_PLAYBACK_GATE_TIMEOUT_MS = 3_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}

private class SppClientAdapter(
    private val client: SppClient,
) : RsmSppConnection {
    override val state: StateFlow<SppState> = client.state
    override fun events(device: BluetoothDevice): Flow<RawButtonEvent> = client.events(device)
    override fun disconnect() = client.disconnect()
}
