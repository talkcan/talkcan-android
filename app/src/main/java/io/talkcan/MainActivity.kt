package io.talkcan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.service.ChannelVoicePreferenceFailure
import io.talkcan.service.ChannelVoicePreferenceMutation
import io.talkcan.model.AppState
import io.talkcan.model.BootstrapState
import io.talkcan.model.ChannelRepositoryMutationResult
import io.talkcan.model.InputMode
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.OfflineNavigationVoiceIssue
import io.talkcan.service.PttForegroundService
import io.talkcan.service.RequiredPermissions
import io.talkcan.ui.BootstrapLoadingScreen
import io.talkcan.ui.CarHfpConfigurationScreen
import io.talkcan.ui.BootstrapRootSurface
import io.talkcan.ui.ChannelConfigurationScreen
import io.talkcan.ui.ConnectionScreen
import io.talkcan.ui.DirectorySelection
import io.talkcan.ui.InitialSetupScreen
import io.talkcan.ui.MainDashboardScreen
import io.talkcan.ui.MonitorScreen
import io.talkcan.ui.LogAnalysisScreen
import io.talkcan.ui.PackageManagementScreen
import io.talkcan.ui.GenericProfileManagementScreen
import io.talkcan.ui.PttUiActions
import io.talkcan.ui.ChannelConfigurationSubmitResult
import io.talkcan.ui.synthesisVoiceChoicesFor
import io.talkcan.ui.bootstrapRootSurface
import io.talkcan.ui.theme.TalkcanTheme

internal fun exitDashboardRoute(
    isPackageManagement: Boolean,
    isVoiceProfiles: Boolean = false,
    cleanup: () -> Unit,
    exitVoiceProfileEditor: () -> Unit = {},
    setMainRoute: () -> Unit,
) {
    if (isPackageManagement) cleanup()
    if (isVoiceProfiles) exitVoiceProfileEditor()
    setMainRoute()
}

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<PttForegroundService?>(null)
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
            service = (binder as PttForegroundService.LocalBinder).service().also { connectedService ->
                connectedService.refreshReadiness()
                connectedService.refreshBootstrapPrerequisites()
            }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val currentService = service
            val currentServiceState by rememberUpdatedState(currentService)
            val state by currentService?.appState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(AppState()) }
            val catalogue by currentService?.repository?.catalogueState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(null) }
            val level by currentService?.level?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(0f) }
            val isCapturing by currentService?.isCapturing?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(false) }
            val bootstrapState by currentService?.bootstrapState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(BootstrapState.ConnectingService) }
            val modelProgress by currentService?.modelAcquisitionProgress?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(io.talkcan.model.ModelAcquisitionProgress()) }
            val providerDescriptors by currentService?.channelDescriptors?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyList()) }
            val dynamicChoiceResolver = currentService?.dynamicChoiceResolver
                ?: io.talkcan.model.DynamicConfigurationChoiceResolver {
                    io.talkcan.model.DynamicConfigurationChoiceResolution.Unavailable(
                        io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY,
                    )
                }
            val logEntries by currentService?.logEntries?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyList()) }
            val currentGlobalLevel by currentService?.globalLogLevelFlow?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(io.talkcan.service.LogLevel.Debug) }
            val currentTagLevels by currentService?.tagLogLevelsFlow?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(emptyMap()) }
            val packageManagementSummary by currentService?.packageManagementState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(io.talkcan.service.PackageManagementSummary(
                    emptyList(),
                    io.talkcan.service.PackageManagementState.Idle,
                    io.talkcan.service.OperationGeneration(0L),
                )) }
            val genericProfileState by currentService?.genericProfileManagementState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(io.talkcan.service.GenericProfileManagementState()) }
            val voiceProfileCatalogue by currentService?.voiceProfileCatalogue?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(io.talkcan.voice.VoiceProfileCatalogue(emptyList(), emptyList())) }
            val voiceProfileEditorState by currentService?.voiceProfileEditorState?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(io.talkcan.service.VoiceProfileEditorState()) }

            var dashboardRoute by rememberSaveable { mutableStateOf(DashboardRoute.Main) }
            var configuredChannelId by rememberSaveable { mutableStateOf<String?>(null) }
            var creatingImplementationId by rememberSaveable { mutableStateOf<String?>(null) }
            var creatingDisplayName by rememberSaveable { mutableStateOf("") }
            var pendingDirectoryOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingDirectoryFieldId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedDirectoryOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedDirectoryFieldId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedDirectoryPath by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingImportDisplayName by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingExportProfileId by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingExportUri by rememberSaveable { mutableStateOf<String?>(null) }
            val directorySelection = selectedDirectoryOwnerId?.let { ownerId ->
                selectedDirectoryFieldId?.let { fieldId ->
                    selectedDirectoryPath?.let { path -> DirectorySelection(ownerId, fieldId, path) }
                }
            }
            val mountTreePickerBridge = remember {
                io.talkcan.mount.saf.SafTreePickerBridge()
            }
            var pendingMountRequest by remember { mutableStateOf<io.talkcan.ui.MountSelectionRequest?>(null) }
            var pendingMountDeclaration by remember { mutableStateOf<io.talkcan.dependency.PackageMountDeclaration?>(null) }
            var pendingMountOutcome by remember {
                mutableStateOf<io.talkcan.mount.saf.SafTreePickerOutcome?>(null)
            }

            LaunchedEffect(
                currentService,
                pendingMountRequest,
                pendingMountDeclaration,
                pendingMountOutcome,
            ) {
                val connectedService = currentService ?: return@LaunchedEffect
                val request = pendingMountRequest ?: return@LaunchedEffect
                val declaration = pendingMountDeclaration ?: return@LaunchedEffect
                val outcome = pendingMountOutcome ?: return@LaunchedEffect

                // Opening the document picker stops this Activity, which
                // temporarily unbinds the service. Re-establish the pending
                // request on the connected service before consuming the
                // picker result.
                connectedService.beginMountSelection(request, declaration)
                connectedService.completeMountSelection(outcome)
                pendingMountRequest = null
                pendingMountDeclaration = null
                pendingMountOutcome = null
            }

            val rootSurface = bootstrapRootSurface(bootstrapState)
            val currentReadyForMonitor by rememberUpdatedState(state.readyForMonitor)
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                currentServiceState?.refreshBootstrapPrerequisites()
            }
            val directoryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val ownerId = pendingDirectoryOwnerId
                val fieldId = pendingDirectoryFieldId
                pendingDirectoryOwnerId = null
                pendingDirectoryFieldId = null
                val path = uri?.let(StoragePathResolver::resolveTreeUri)
                if (ownerId != null && fieldId != null && path != null) {
                    selectedDirectoryOwnerId = ownerId
                    selectedDirectoryFieldId = fieldId
                    selectedDirectoryPath = path
                }
            }
            val mountLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                pendingMountOutcome = mountTreePickerBridge.outcomeFrom(result.data)
            }

            val voiceSetupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) {
                currentServiceState?.refreshBootstrapPrerequisites()
            }
            val importProfileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    pendingImportUri = uri.toString()
                } else {
                    pendingImportDisplayName = null
                }
            }
            val exportProfileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri != null) {
                    pendingExportUri = uri.toString()
                } else {
                    pendingExportProfileId = null
                }
            }

            LaunchedEffect(pendingImportUri, pendingImportDisplayName, currentServiceState) {
                val uriStr = pendingImportUri
                val displayName = pendingImportDisplayName
                val service = currentServiceState
                if (uriStr != null && displayName != null && service != null) {
                    try {
                        contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { input ->
                            service.completeVoiceProfileImport(input, displayName)
                        }
                    } finally {
                        pendingImportUri = null
                        pendingImportDisplayName = null
                    }
                }
            }

            LaunchedEffect(pendingExportUri, pendingExportProfileId, currentServiceState) {
                val uriStr = pendingExportUri
                val profileId = pendingExportProfileId
                val service = currentServiceState
                if (uriStr != null && profileId != null && service != null) {
                    try {
                        contentResolver.openOutputStream(android.net.Uri.parse(uriStr))?.use { output ->
                            service.completeVoiceProfileExport(profileId, output)
                        }
                    } finally {
                        pendingExportUri = null
                        pendingExportProfileId = null
                    }
                }
            }

            val actions = remember(currentService, permissionLauncher, directoryLauncher, mountLauncher, providerDescriptors) {
                object : PttUiActions {
                    override fun requestPermissions() {
                        permissionLauncher.launch(RequiredPermissions.runtimePermissions())
                    }

                    override fun pickMount(request: io.talkcan.ui.MountSelectionRequest) {
                        val service = currentServiceState ?: return
                        val descriptor = providerDescriptors.firstOrNull { it.implementationId == request.implementationId }
                            ?: return
                        val declaration = descriptor.resourceDeclarations.mounts.firstOrNull { it.id == request.declarationId }
                            ?: return
                        service.beginMountSelection(request, declaration)
                        pendingMountRequest = request
                        pendingMountDeclaration = declaration
                        val intent = mountTreePickerBridge.launchIntent(declaration)
                        mountLauncher.launch(intent)
                    }
                    override fun requestManageExternalStorage() {
                        startActivity(RequiredPermissions.manageExternalStorageIntent(this@MainActivity))
                    }

                    override fun pickDirectory(configurationOwnerId: String, fieldId: String) {
                        pendingDirectoryOwnerId = configurationOwnerId
                        pendingDirectoryFieldId = fieldId
                        directoryLauncher.launch(null)
                    }

                    override fun openBluetoothSettings() {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }

                    override fun scanForDevice() {
                        currentServiceState?.scanForDevice()
                    }

                    override fun pairTarget() {
                        currentServiceState?.pairTarget()
                    }

                    override fun refreshCarHfpConfiguration() {
                        currentServiceState?.refreshCarHfpConfiguration()
                    }

                    override fun selectCarHfpCandidate(selectionId: String) {
                        currentServiceState?.selectCarHfpCandidate(selectionId)
                    }

                    override fun connectSerial() {
                        currentServiceState?.connectSerial()
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, PttForegroundService::class.java)
                                .setAction(PttForegroundService.ACTION_START_MONITORING),
                        )
                    }

                    override fun retry() {
                        currentServiceState?.refreshReadiness()
                    }

                    override fun disconnectSerial() {
                        currentServiceState?.disconnectSerial()
                    }

                    override fun setActiveChannel(id: String) {
                        currentServiceState?.selectChannel(id)
                    }

                    override fun setInputMode(mode: InputMode) {
                        currentServiceState?.setInputMode(mode)
                    }

                    override fun navigateToRsmSetup() {
                        dashboardRoute = if (currentReadyForMonitor) DashboardRoute.Monitor else DashboardRoute.Connection
                    }

                    override fun navigateToCarSetup() {
                        currentServiceState?.refreshCarHfpConfiguration()
                        dashboardRoute = DashboardRoute.CarConfiguration
                    }

                    override fun navigateToChannelConfiguration(channelId: String) {
                        configuredChannelId = channelId
                        creatingImplementationId = null
                        dashboardRoute = DashboardRoute.ChannelConfiguration
                    }

                    override fun navigateToChannelCreation(
                        implementationId: io.talkcan.model.ChannelImplementationId,
                        displayName: String,
                    ) {
                        configuredChannelId = null
                        creatingImplementationId = implementationId.value
                        creatingDisplayName = displayName
                        dashboardRoute = DashboardRoute.ChannelCreation
                    }

                    override fun navigateBack() {
                        exitDashboardRoute(
                            isPackageManagement = dashboardRoute == DashboardRoute.PackageManagement,
                            isVoiceProfiles = dashboardRoute == DashboardRoute.VoiceProfiles,
                            cleanup = {
                                currentServiceState?.cleanupPackageManagementRouteExit()
                            },
                            exitVoiceProfileEditor = {
                                currentServiceState?.exitVoiceProfileEditor()
                            },
                            setMainRoute = {
                                dashboardRoute = DashboardRoute.Main
                            },
                        )
                        configuredChannelId = null
                        creatingImplementationId = null
                    }

                    override fun navigateToLogAnalysis() {
                        dashboardRoute = DashboardRoute.LogAnalysis
                    }


                    override fun navigateToPackageManagement() {
                        dashboardRoute = DashboardRoute.PackageManagement
                    }

                    override fun navigateToGenericProfiles() {
                        dashboardRoute = DashboardRoute.GenericProfiles
                    }
                    override fun navigateToVoiceProfiles() {
                        dashboardRoute = DashboardRoute.VoiceProfiles
                    }

                    override fun createGenericProfile(
                        identity: io.talkcan.profile.ProfileTypeIdentity,
                        displayName: String,
                        scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
                        secrets: Map<String, CharSequence>,
                    ) {
                        currentServiceState?.createGenericProfile(identity, displayName, scalars, secrets)
                    }

                    override fun updateGenericProfile(
                        profileId: io.talkcan.profile.ProfileId,
                        displayName: String,
                        scalars: Map<String, io.talkcan.profile.ProfileScalarValue>,
                        secretEdits: Map<String, io.talkcan.profile.SecretEditAction>,
                    ) {
                        currentServiceState?.updateGenericProfile(profileId, displayName, scalars, secretEdits)
                    }

                    override fun deleteGenericProfile(profileId: io.talkcan.profile.ProfileId) {
                        currentServiceState?.deleteGenericProfile(profileId)
                    }

                    override fun resolvePackageRepository(url: String) {
                        currentServiceState?.resolvePackageRepository(url)
                    }

                    override fun selectPackageRelease(releaseId: String) {
                        currentServiceState?.selectPackageRelease(releaseId)
                    }

                    override fun confirmPackageInstall(acknowledged: Boolean) {
                        currentServiceState?.confirmPackageInstall(acknowledged)
                    }

                    override fun rollbackPackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity) {
                        currentServiceState?.rollbackPackage(repositoryId)
                    }

                    override fun removePackage(repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity) {
                        currentServiceState?.removePackage(repositoryId)
                    }

                    override fun cancelPackageInspection() {
                        currentServiceState?.cancelPackageInspection()
                    }


                    override fun refreshPackageManagement(url: String) {
                        currentServiceState?.refreshPackageManagement(url)
                    }

                    override fun phonePttPressed(channelId: String) {
                        currentServiceState?.startPhonePtt(channelId)
                    }

                    override fun phonePttReleased(channelId: String) {
                        currentServiceState?.phonePttReleased(channelId)
                    }

                    override fun createChannel(
                        implementationId: io.talkcan.model.ChannelImplementationId,
                        displayName: String,
                        payload: OpaqueJsonObject,
                    ): String? = currentServiceState
                        ?.createChannel(implementationId, displayName, payload)
                        .failureMessage()

                    override fun updateChannelConfiguration(
                        channelId: String,
                        payload: OpaqueJsonObject,
                    ): String? = currentServiceState
                        ?.updateChannelConfiguration(channelId, payload)
                        .failureMessage()

                    override fun removeChannel(id: String) {
                        currentServiceState?.repository?.removeChannel(id)
                    }

                    override fun moveChannel(id: String, toIndex: Int) {
                        currentServiceState?.repository?.moveChannel(id, toIndex)
                    }

                    override fun renameChannel(id: String, newName: String) {
                        currentServiceState?.repository?.updateChannel(id) { definition ->
                            definition.copy(name = newName)
                        }
                    }
                }
            }

            TalkcanTheme {
                Surface {
                    when (rootSurface) {
                        BootstrapRootSurface.Loading -> {
                            BackHandler(enabled = false) { }
                            BootstrapLoadingScreen(
                                state = bootstrapState,
                                modelProgress = modelProgress,
                                onRetry = { currentServiceState?.retryBootstrap() },
                            )
                        }

                        BootstrapRootSurface.Setup -> {
                            val setup = bootstrapState as BootstrapState.NeedsSetup
                            BackHandler(enabled = false) { }
                            val voiceIssue = setup.offlineNavigationVoiceIssue
                            val voiceSetupIntent = remember(voiceIssue) {
                                resolveVoiceSetupIntent(this, voiceIssue)
                            }
                            InitialSetupScreen(
                                missingPermissions = setup.missingPermissions,
                                needsManageExternalStorage = setup.needsManageExternalStorage,
                                invalidModelSets = setup.invalidModelSets,
                                error = setup.error,
                                offlineNavigationVoiceIssue = voiceIssue,
                                voiceSetupRequiresManualNavigation =
                                    voiceSetupIntent.action == Settings.ACTION_SETTINGS,
                                onGrantPermissions = {
                                    permissionLauncher.launch(RequiredPermissions.runtimePermissions())
                                },
                                onGrantManageExternalStorage = actions::requestManageExternalStorage,
                                onStartModelDownload = { currentServiceState?.startModelAcquisition() },
                                onResolveVoiceSetup = {
                                    voiceSetupLauncher.launch(voiceSetupIntent)
                                },
                            )
                        }

                        BootstrapRootSurface.Dashboard -> {
                            BackHandler(enabled = dashboardRoute != DashboardRoute.Main) { actions.navigateBack() }
                            when (dashboardRoute) {
                                DashboardRoute.Main -> MainDashboardScreen(
                                    appState = state,
                                    level = level,
                                    isCapturing = isCapturing,
                                    providerDescriptors = providerDescriptors,
                                    actions = actions,
                                )

                                DashboardRoute.Connection -> ConnectionScreen(state.connection, actions)
                                DashboardRoute.Monitor -> MonitorScreen(state.monitor, actions)
                                DashboardRoute.CarConfiguration -> CarHfpConfigurationScreen(
                                    state = state.carHfpConfiguration,
                                    actions = actions,
                                )
                                DashboardRoute.ChannelConfiguration -> {
                                    val definition = catalogue?.definitions?.firstOrNull { it.id == configuredChannelId }
                                    val descriptor = definition?.let { target ->
                                        providerDescriptors.firstOrNull {
                                            it.implementationId == target.implementationId
                                        }
                                    }
                                    if (definition != null && descriptor != null) {
                                        ChannelConfigurationScreen(
                                            title = definition.name,
                                            configurationOwnerId = definition.id,
                                            descriptor = descriptor,
                                            initialPayload = definition.configPayload,
                                            submitLabel = "Save configuration",
                                            onSubmit = { payload ->
                                                actions.updateChannelConfiguration(definition.id, payload).also { error ->
                                                    if (error == null) actions.navigateBack()
                                                }
                                            },
                                            choiceResolver = dynamicChoiceResolver,
                                            directorySelection = directorySelection,
                                            onPickDirectory = actions::pickDirectory,
                                            mountEntries = currentService?.mountEditorEntries(definition.id, definition.implementationId) ?: emptyList(),
                                             onPickMount = actions::pickMount,
                                             onBack = actions::navigateBack,
                                            initialSynthesisVoiceProfileId = definition.hostPreferences.synthesisVoiceProfileId?.value,
                                            synthesisVoiceChoices = synthesisVoiceChoicesFor(
                                                catalogue = voiceProfileCatalogue,
                                                currentSelectionId = definition.hostPreferences.synthesisVoiceProfileId,
                                            ),
                                            onCommitWithVoice = if (descriptor.capabilities.contains(ChannelCapability.Synthesis)) {
                                                { payload, profileId, acknowledgeUnverified ->
                                                    val providerError = actions.updateChannelConfiguration(definition.id, payload)
                                                    if (providerError != null) {
                                                        ChannelConfigurationSubmitResult.Error(providerError)
                                                    } else {
                                                        when (
                                                            val mutation = currentServiceState?.updateChannelSynthesisVoiceProfile(
                                                                 channelId = definition.id,
                                                                profileId = profileId,
                                                                acknowledgeUnverified = acknowledgeUnverified,
                                                            )
                                                        ) {
                                                            is ChannelVoicePreferenceMutation.Committed -> {
                                                                actions.navigateBack()
                                                                ChannelConfigurationSubmitResult.Success
                                                            }
                                                            is ChannelVoicePreferenceMutation.VoiceRefused -> {
                                                                when (val failure = mutation.failure) {
                                                                    is ChannelVoicePreferenceFailure.UnverifiedRequiresAcknowledgement -> {
                                                                        ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired(
                                                                            profileId = failure.profileId,
                                                                            displayName = failure.displayName,
                                                                            diagnostic = failure.diagnostic,
                                                                        )
                                                                    }
                                                                    else -> {
                                                                        ChannelConfigurationSubmitResult.Error(failure.diagnostic)
                                                                    }
                                                                }
                                                            }
                                                            is ChannelVoicePreferenceMutation.ChannelRefused -> {
                                                                ChannelConfigurationSubmitResult.Error(mutation.error.message)
                                                            }
                                                            null -> {
                                                                actions.navigateBack()
                                                                ChannelConfigurationSubmitResult.Success
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                null
                                            },
                                         )
                                    }
                                }

                                DashboardRoute.ChannelCreation -> {
                                    val descriptor = providerDescriptors.firstOrNull {
                                        it.implementationId.value == creatingImplementationId
                                    }
                                    if (descriptor != null) {
                                        ChannelConfigurationScreen(
                                            title = "New ${descriptor.presentation.label}",
                                            configurationOwnerId = "new:${descriptor.implementationId.value}:$creatingDisplayName",
                                            descriptor = descriptor,
                                            initialPayload = descriptor.configuration.defaultPayload(),
                                            submitLabel = "Create channel",
                                            onSubmit = { payload ->
                                                actions.createChannel(
                                                    descriptor.implementationId,
                                                    creatingDisplayName,
                                                    payload,
                                                ).also { error ->
                                                    if (error == null) actions.navigateBack()
                                                }
                                            },
                                            choiceResolver = dynamicChoiceResolver,
                                            directorySelection = directorySelection,
                                            onPickDirectory = actions::pickDirectory,
                                            mountEntries = emptyList(),
                                             onPickMount = actions::pickMount,
                                             onBack = actions::navigateBack,
                                            initialSynthesisVoiceProfileId = null,
                                            synthesisVoiceChoices = synthesisVoiceChoicesFor(
                                                catalogue = voiceProfileCatalogue,
                                                currentSelectionId = null,
                                            ),
                                            onCommitWithVoice = if (descriptor.capabilities.contains(ChannelCapability.Synthesis)) {
                                                { payload, profileId, acknowledgeUnverified ->
                                                    when (
                                                        val mutation = currentServiceState?.createChannelWithVoice(
                                                            implementationId = descriptor.implementationId,
                                                            name = creatingDisplayName,
                                                            payload = payload,
                                                            voiceProfileId = profileId,
                                                            acknowledgeUnverified = acknowledgeUnverified,
                                                        )
                                                    ) {
                                                        is ChannelVoicePreferenceMutation.Committed -> {
                                                            actions.navigateBack()
                                                            ChannelConfigurationSubmitResult.Success
                                                        }
                                                        is ChannelVoicePreferenceMutation.VoiceRefused -> {
                                                            when (val failure = mutation.failure) {
                                                                is ChannelVoicePreferenceFailure.UnverifiedRequiresAcknowledgement -> {
                                                                    ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired(
                                                                        profileId = failure.profileId,
                                                                        displayName = failure.displayName,
                                                                        diagnostic = failure.diagnostic,
                                                                    )
                                                                }
                                                                else -> {
                                                                    ChannelConfigurationSubmitResult.Error(failure.diagnostic)
                                                                }
                                                            }
                                                        }
                                                        is ChannelVoicePreferenceMutation.ChannelRefused -> {
                                                            ChannelConfigurationSubmitResult.Error(mutation.error.message)
                                                        }
                                                        null -> {
                                                            val err = actions.createChannel(
                                                                descriptor.implementationId,
                                                                creatingDisplayName,
                                                                payload,
                                                             )
                                                            if (err == null) {
                                                                actions.navigateBack()
                                                                ChannelConfigurationSubmitResult.Success
                                                            } else {
                                                                ChannelConfigurationSubmitResult.Error(err)
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                null
                                            },
                                         )
                                    }
                                }

                                DashboardRoute.LogAnalysis -> {
                                    LogAnalysisScreen(
                                        entries = logEntries,
                                        onClear = { currentServiceState?.clearLogs() },
                                        onSetGlobalLevel = { level ->
                                            currentServiceState?.setGlobalLogLevel(level)
                                        },
                                        onSetTagLevel = { tag, level ->
                                            currentServiceState?.setTagLogLevel(tag, level)
                                        },
                                        onClearTagLevel = { tag ->
                                            currentServiceState?.clearTagLogLevel(tag)
                                        },
                                        currentGlobalLevel = currentGlobalLevel,
                                        tagLevels = currentTagLevels,
                                    )
                                }


                                DashboardRoute.PackageManagement -> {
                                    PackageManagementScreen(
                                        summary = packageManagementSummary,
                                        profileState = genericProfileState,
                                        actions = actions,
                                    )
                                }

                                DashboardRoute.GenericProfiles -> GenericProfileManagementScreen(
                                    state = genericProfileState,
                                    actions = actions,
                                )
                                DashboardRoute.VoiceProfiles -> io.talkcan.ui.VoiceProfileManagementScreen(
                                    catalogue = voiceProfileCatalogue,
                                    editorState = voiceProfileEditorState,
                                    onSelectSources = { ids, discard ->
                                        currentServiceState?.selectVoiceProfileSources(ids.map { it.value }, discard)
                                    },
                                    onSetEqualWeights = {
                                        currentServiceState?.setVoiceProfileEqualWeights()
                                    },
                                    onSetManualWeights = { weights ->
                                        currentServiceState?.setVoiceProfileManualWeights(weights)
                                    },
                                    onSetRandomWeights = { seed ->
                                        currentServiceState?.setVoiceProfileRandomWeights(seed)
                                    },
                                    onApplyOperation = { op ->
                                        currentServiceState?.applyVoiceProfileOperation(op)
                                    },
                                    onUndoOperation = {
                                        currentServiceState?.undoVoiceProfileOperation()
                                    },
                                    onResetDraft = {
                                        currentServiceState?.resetVoiceProfileDraft()
                                    },
                                    onAcknowledgeFailure = {
                                        currentServiceState?.acknowledgeVoiceProfileFailure()
                                    },
                                    onSaveDraftAsNew = { name ->
                                        currentServiceState?.saveVoiceProfileDraftAsNew(name)
                                    },
                                    onRenameProfile = { id, name ->
                                        currentServiceState?.renameVoiceProfile(id.value, name)
                                    },
                                    onDeleteProfile = { id ->
                                        currentServiceState?.deleteVoiceProfile(id.value)
                                    },
                                    onImportProfile = { name ->
                                        pendingImportDisplayName = name
                                        importProfileLauncher.launch(arrayOf("application/json", "*/*"))
                                    },
                                    onExportProfile = { id, filename ->
                                        pendingExportProfileId = id
                                        exportProfileLauncher.launch(filename)
                                    },
                                    onPreviewDraft = { text ->
                                        currentServiceState?.previewVoiceProfileDraft(text)
                                    },
                                    onCancelPreview = {
                                        currentServiceState?.cancelVoiceProfilePreview()
                                    },
                                    onExitEditor = {
                                        currentServiceState?.exitVoiceProfileEditor()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.startForegroundService(
            this,
            Intent(this, PttForegroundService::class.java)
                .setAction(PttForegroundService.ACTION_START_MONITORING),
        )
        bindService(
            Intent(this, PttForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onResume() {
        super.onResume()
        service?.refreshReadiness()
    }

    override fun onStop() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        service = null
        super.onStop()
    }

    private enum class DashboardRoute {
        Main,
        Connection,
        Monitor,
        CarConfiguration,
        ChannelConfiguration,
        ChannelCreation,
        LogAnalysis,
        PackageManagement,
        GenericProfiles,
        VoiceProfiles,
    }

}

internal fun resolveVoiceSetupIntent(
    context: Context,
    issue: OfflineNavigationVoiceIssue?,
): Intent {
    val enginePackage = issue?.enginePackage
    if (enginePackage != null) {
        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            .setPackage(enginePackage)
        val handlers = context.packageManager.queryIntentActivities(
            installIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (handlers.isNotEmpty()) {
            return installIntent
        }
    }
    return Intent(Settings.ACTION_SETTINGS)
}

internal fun ChannelRepositoryMutationResult?.failureMessage(): String? = when (this) {
    null -> "Channel service is unavailable."
    ChannelRepositoryMutationResult.Success -> null
    is ChannelRepositoryMutationResult.Failure -> error.message
}

internal object StoragePathResolver {
    fun resolveTreeUri(uri: android.net.Uri): String? {
        val documentId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val decoded = java.net.URLDecoder.decode(documentId, java.nio.charset.StandardCharsets.UTF_8.name())
        val parts = decoded.split(':', limit = 2)
        val volume = parts.getOrNull(0).orEmpty()
        val relative = parts.getOrNull(1).orEmpty().trim('/')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> android.os.Environment.getExternalStorageDirectory()
            volume.isNotBlank() -> java.io.File("/storage", volume)
            else -> return null
        }
        return if (relative.isBlank()) root.absolutePath else java.io.File(root, relative).absolutePath
    }
}