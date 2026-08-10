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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
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
import io.talkcan.ui.ChannelManagementScreen
import io.talkcan.ui.ConnectionScreen
import io.talkcan.ui.DirectorySelection
import io.talkcan.ui.InitialSetupScreen
import io.talkcan.ui.MainDashboardScreen
import io.talkcan.ui.MonitorScreen
import io.talkcan.ui.LogAnalysisScreen
import io.talkcan.ui.PackageManagementScreen
import io.talkcan.ui.GenericProfileManagementScreen
import io.talkcan.ui.SettingsHomeScreen
import io.talkcan.ui.PttUiActions
import io.talkcan.ui.ChannelConfigurationSubmitResult
import io.talkcan.ui.synthesisVoiceChoicesFor
import io.talkcan.ui.bootstrapRootSurface
import io.talkcan.ui.theme.TalkcanTheme
import io.talkcan.ui.theme.Graphite
import io.talkcan.ui.theme.MutedSteel
import io.talkcan.ui.theme.SignalAmber
import io.talkcan.ui.theme.WarmAluminum


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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT,
            ),
        )

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

            val missingPermissions = (bootstrapState as? BootstrapState.NeedsSetup)?.missingPermissions
                ?: io.talkcan.service.RequiredPermissions.missing(this@MainActivity)
            val permissionsReady = missingPermissions.isEmpty()

            val needsManageExternalStorage = (bootstrapState as? BootstrapState.NeedsSetup)?.needsManageExternalStorage
                ?: !io.talkcan.service.RequiredPermissions.hasManageExternalStorage()
            val storageReady = !needsManageExternalStorage

            val modelsReady = when (bootstrapState) {
                is BootstrapState.Ready -> true
                is BootstrapState.PreparingCore -> true
                is BootstrapState.Failed -> {
                    val failedState = bootstrapState as BootstrapState.Failed
                    failedState.stage != io.talkcan.model.BootstrapStage.CheckingModels && failedState.stage != io.talkcan.model.BootstrapStage.AcquiringModels
                }
                is BootstrapState.NeedsSetup -> {
                    (bootstrapState as BootstrapState.NeedsSetup).invalidModelSets.isEmpty()
                }
                else -> false
            }
            val invalidModelSets = (bootstrapState as? BootstrapState.NeedsSetup)?.invalidModelSets ?: emptyList()

            val voiceReady = when (bootstrapState) {
                is BootstrapState.Ready -> true
                is BootstrapState.PreparingCore -> true
                is BootstrapState.NeedsSetup -> {
                    (bootstrapState as BootstrapState.NeedsSetup).offlineNavigationVoiceIssue == null
                }
                else -> false
            }
            val offlineNavigationVoiceIssue = (bootstrapState as? BootstrapState.NeedsSetup)?.offlineNavigationVoiceIssue
            val voiceSetupIntent = remember(offlineNavigationVoiceIssue) {
                resolveVoiceSetupIntent(
                    this@MainActivity,
                    offlineNavigationVoiceIssue,
                )
            }
            val voiceSetupRequiresManualNavigation = voiceSetupIntent.action == Settings.ACTION_SETTINGS
            val setupError = (bootstrapState as? BootstrapState.NeedsSetup)?.error

            var appSection by rememberSaveable { mutableStateOf(AppSection.Radio) }
            var secondaryRoute by rememberSaveable { mutableStateOf<SecondaryRoute?>(null) }
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


            fun applyNavigation(action: NavigationAction) {
                val current = NavigationState(
                    appSection = appSection,
                    secondaryRoute = secondaryRoute,
                    configuredChannelId = configuredChannelId,
                    creatingImplementationId = creatingImplementationId,
                    creatingDisplayName = creatingDisplayName
                )
                val next = navigate(current, action)
                appSection = next.appSection
                secondaryRoute = next.secondaryRoute
                configuredChannelId = next.configuredChannelId
                creatingImplementationId = next.creatingImplementationId
                creatingDisplayName = next.creatingDisplayName
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

                    override fun navigateToRadio() {
                        applyNavigation(NavigationAction.NavigateToRadio)
                    }

                    override fun navigateToSettingsHome() {
                        applyNavigation(NavigationAction.NavigateToSettingsHome)
                    }

                    override fun navigateToRsmSetup() {
                        applyNavigation(NavigationAction.NavigateToRsmSetup(currentReadyForMonitor))
                    }

                    override fun navigateToCarSetup() {
                        currentServiceState?.refreshCarHfpConfiguration()
                        applyNavigation(NavigationAction.NavigateToCarSetup)
                    }

                    override fun navigateToChannelConfiguration(channelId: String) {
                        applyNavigation(NavigationAction.NavigateToChannelConfiguration(channelId))
                    }

                    override fun navigateToChannelManagement() {
                        applyNavigation(NavigationAction.NavigateToChannelManagement)
                    }

                    override fun navigateToChannelCreation(
                        implementationId: io.talkcan.model.ChannelImplementationId,
                        displayName: String,
                    ) {
                        applyNavigation(
                            NavigationAction.NavigateToChannelCreation(
                                implementationId.value,
                                displayName
                            )
                        )
                    }

                    override fun navigateBack() {
                        applyNavigation(
                            NavigationAction.NavigateBack(
                                cleanupPackageManagement = {
                                    currentServiceState?.cleanupPackageManagementRouteExit()
                                },
                                exitVoiceProfileEditor = {
                                    currentServiceState?.exitVoiceProfileEditor()
                                }
                            )
                        )
                    }

                    override fun navigateToLogAnalysis() {
                        applyNavigation(NavigationAction.NavigateToLogAnalysis)
                    }

                    override fun navigateToPackageManagement() {
                        applyNavigation(NavigationAction.NavigateToPackageManagement)
                    }

                    override fun navigateToGenericProfiles() {
                        applyNavigation(NavigationAction.NavigateToGenericProfiles)
                    }

                    override fun navigateToVoiceProfiles() {
                        applyNavigation(NavigationAction.NavigateToVoiceProfiles)
                    }

                    override fun navigateToSystemReadiness() {
                        applyNavigation(NavigationAction.NavigateToSystemReadiness)
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                    ) {
                        when (rootSurface) {
                        BootstrapRootSurface.Loading -> {
                            BackHandler(enabled = false) { }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .safeDrawingPadding(),
                            ) {
                                BootstrapLoadingScreen(
                                    state = bootstrapState,
                                    modelProgress = modelProgress,
                                    onRetry = { currentServiceState?.retryBootstrap() },
                                )
                            }
                        }

                        BootstrapRootSurface.Setup -> {
                            val setup = bootstrapState as BootstrapState.NeedsSetup
                            BackHandler(enabled = false) { }
                            val voiceIssue = setup.offlineNavigationVoiceIssue
                            val voiceSetupIntent = remember(voiceIssue) {
                                resolveVoiceSetupIntent(
                                    this@MainActivity,
                                    voiceIssue,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .safeDrawingPadding(),
                            ) {
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
                        }

                        BootstrapRootSurface.Dashboard -> {
                            BackHandler(enabled = appSection != AppSection.Radio || secondaryRoute != null) { actions.navigateBack() }
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                bottomBar = {
                                    val navigationItemColors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = SignalAmber,
                                            selectedTextColor = SignalAmber,
                                            unselectedIconColor =
                                                WarmAluminum.copy(alpha = 0.72f),
                                            unselectedTextColor =
                                                WarmAluminum.copy(alpha = 0.72f),
                                            indicatorColor = Graphite,
                                        )
                                    NavigationBar(
                                        modifier = Modifier.border(
                                            width = 1.dp,
                                            color = MutedSteel,
                                        ),
                                        containerColor = Graphite,
                                        tonalElevation = 0.dp,
                                    ) {
                                        NavigationBarItem(
                                            selected = appSection == AppSection.Radio,
                                            onClick = actions::navigateToRadio,
                                            icon = {
                                                Icon(
                                                    imageVector = Icons.Filled.Call,
                                                    contentDescription = "Radio",
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = "Radio",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            },
                                            colors = navigationItemColors,
                                        )
                                        NavigationBarItem(
                                            selected = appSection == AppSection.Settings,
                                            onClick = actions::navigateToSettingsHome,
                                            icon = {
                                                Icon(
                                                    imageVector = Icons.Filled.Settings,
                                                    contentDescription = "Settings",
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = "Settings",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            },
                                            colors = navigationItemColors,
                                        )
                                    }
                                }
                            ) { paddingValues ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(paddingValues),
                                ) {
                                    if (appSection == AppSection.Radio) {
                                MainDashboardScreen(
                                    appState = state,
                                    level = level,
                                    isCapturing = isCapturing,
                                    providerDescriptors = providerDescriptors,
                                    actions = actions,
                                )
                            } else {
                                val currentRoute = secondaryRoute
                                if (currentRoute != null) {
                                    Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        topBar = {
                                            TopAppBar(
                                                title = {
                                                    val routeTitle = when (currentRoute) {
                                                        SecondaryRoute.Connection -> "Radio connection"
                                                        SecondaryRoute.Monitor -> "Hardware monitor"
                                                        SecondaryRoute.CarConfiguration -> "Car headset"
                                                        SecondaryRoute.ChannelConfiguration -> {
                                                            val definition = catalogue?.definitions?.firstOrNull { it.id == configuredChannelId }
                                                            definition?.name ?: "Channel configuration"
                                                        }
                                                        SecondaryRoute.ChannelManagement -> "Channel management"
                                                        SecondaryRoute.ChannelCreation -> {
                                                            val descriptor = providerDescriptors.firstOrNull {
                                                                it.implementationId.value == creatingImplementationId
                                                            }
                                                            descriptor?.let { "New ${it.presentation.label}" } ?: "Channel creation"
                                                        }
                                                        SecondaryRoute.LogAnalysis -> "Diagnostic logs"
                                                        SecondaryRoute.PackageManagement -> "Installed providers"
                                                        SecondaryRoute.GenericProfiles -> "Provider profiles"
                                                        SecondaryRoute.VoiceProfiles -> "Voice profiles"
                                                        SecondaryRoute.SystemReadiness -> "System readiness"
                                                    }
                                                    Text(
                                                        text = routeTitle,
                                                        style = MaterialTheme.typography.titleMedium,
                                                    )
                                                },
                                                navigationIcon = {
                                                    IconButton(onClick = actions::navigateBack) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                            contentDescription = "Back",
                                                        )
                                                    }
                                                },
                                                colors = TopAppBarDefaults.topAppBarColors(
                                                    containerColor = Graphite,
                                                    navigationIconContentColor = WarmAluminum,
                                                    titleContentColor = WarmAluminum,
                                                ),
                                            )
                                        },
                                    ) { innerPadding ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(innerPadding),
                                        ) {
                                            when (currentRoute) {
                                                SecondaryRoute.Connection -> ConnectionScreen(state.connection, actions)
                                                SecondaryRoute.Monitor -> MonitorScreen(state.monitor, actions)
                                                SecondaryRoute.CarConfiguration -> CarHfpConfigurationScreen(
                                                    state = state.carHfpConfiguration,
                                                    actions = actions,
                                                )
                                                SecondaryRoute.ChannelConfiguration -> {
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
                                                                                val err = actions.updateChannelConfiguration(definition.id, payload)
                                                                                if (err == null) {
                                                                                    actions.navigateBack()
                                                                                    ChannelConfigurationSubmitResult.Success
                                                                                } else {
                                                                                    ChannelConfigurationSubmitResult.Error(err)
                                                                                }
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

                                                SecondaryRoute.ChannelCreation -> {
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

                                                SecondaryRoute.ChannelManagement -> ChannelManagementScreen(
                                                    appState = state,
                                                    providerDescriptors = providerDescriptors,
                                                    actions = actions,
                                                )

                                                SecondaryRoute.LogAnalysis -> {
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

                                                SecondaryRoute.PackageManagement -> {
                                                    PackageManagementScreen(
                                                        summary = packageManagementSummary,
                                                        profileState = genericProfileState,
                                                        actions = actions,
                                                    )
                                                }

                                                SecondaryRoute.GenericProfiles -> GenericProfileManagementScreen(
                                                    state = genericProfileState,
                                                    actions = actions,
                                                )
                                                SecondaryRoute.VoiceProfiles -> io.talkcan.ui.VoiceProfileManagementScreen(
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
                                                SecondaryRoute.SystemReadiness -> {
                                                    io.talkcan.ui.SystemReadinessScreen(
                                                        permissionsReady = permissionsReady,
                                                        missingPermissions = missingPermissions,
                                                        storageReady = storageReady,
                                                        modelsReady = modelsReady,
                                                        invalidModelSets = invalidModelSets,
                                                        voiceReady = voiceReady,
                                                        offlineNavigationVoiceIssue = offlineNavigationVoiceIssue,
                                                        voiceSetupRequiresManualNavigation = voiceSetupRequiresManualNavigation,
                                                        error = setupError,
                                                        modelProgress = modelProgress,
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
                                            }
                                        }
                                    }
                                } else {
                                    SettingsHomeScreen(
                                        onRsmClick = actions::navigateToRsmSetup,
                                        onCarClick = actions::navigateToCarSetup,
                                        onChannelManagementClick = actions::navigateToChannelManagement,
                                        onInstalledProvidersClick = actions::navigateToPackageManagement,
                                        onProviderProfilesClick = actions::navigateToGenericProfiles,
                                        onVoiceProfilesClick = actions::navigateToVoiceProfiles,
                                        onLogsClick = actions::navigateToLogAnalysis,
                                        onSystemReadinessClick = actions::navigateToSystemReadiness,
                                        permissionsReady = permissionsReady,
                                        modelsReady = modelsReady,
                                        voiceReady = voiceReady,
                                        storageReady = storageReady,
                                    )
                                }
                            }
                        }
                    }
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