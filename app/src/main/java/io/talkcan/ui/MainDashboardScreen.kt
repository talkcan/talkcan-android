package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.talkcan.model.AppState
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.InputMode
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelRuntimeSnapshot
import io.talkcan.ui.theme.CanRed
import io.talkcan.ui.theme.Ink
import io.talkcan.ui.theme.StringYellow

@Composable
fun MainDashboardScreen(
    appState: AppState,
    level: Float,
    isCapturing: Boolean,
    providerDescriptors: List<ChannelImplementationDescriptor>,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val phonePttLockThresholdPx = with(LocalDensity.current) { PhonePttLockThreshold.toPx() }
    var phonePttGesture by remember { mutableStateOf<PhonePttGestureState>(PhonePttGestureState.Idle) }

    fun applyPhonePttTransition(transition: PhonePttGestureTransition) {
        phonePttGesture = transition.state
        for (command in transition.commands) {
            val channelId = when (val state = transition.state) {
                PhonePttGestureState.Idle -> return
                is PhonePttGestureState.Armed -> state.channelId
                is PhonePttGestureState.Locked -> state.channelId
                is PhonePttGestureState.Finalized -> state.channelId
            }
            when (command) {
                PhonePttGestureCommand.Press -> actions.phonePttPressed(channelId)
                PhonePttGestureCommand.Release -> actions.phonePttReleased(channelId)
            }
        }
        if (transition.state is PhonePttGestureState.Finalized) {
            phonePttGesture = PhonePttGestureState.Idle
        }
    }

    val currentPhonePttGesture by rememberUpdatedState(phonePttGesture)
    val currentApplyPhonePttTransition by rememberUpdatedState<(PhonePttGestureTransition) -> Unit> {
        applyPhonePttTransition(it)
    }

    LaunchedEffect(isCapturing) {
        currentApplyPhonePttTransition(currentPhonePttGesture.captureChangedPhonePttGesture(isCapturing))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                currentApplyPhonePttTransition(currentPhonePttGesture.focusLostPhonePttGesture())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        DashboardHeader(
            appState = appState,
            isCapturing = isCapturing,
            onActivityLog = actions::navigateToLogAnalysis,
        )

        DashboardTalkLevel(
            level = level,
            isCapturing = isCapturing,
        )

        InputModeSelector(appState, actions)

        ChannelPanel(
            appState = appState,
            providerDescriptors = providerDescriptors,
            actions = actions,
            phonePttGesture = phonePttGesture,
            phonePttLockThresholdPx = phonePttLockThresholdPx,
            onPhonePttTransition = ::applyPhonePttTransition,
        )
    }
}

@Composable
private fun DashboardHeader(
    appState: AppState,
    isCapturing: Boolean,
    onActivityLog: () -> Unit,
) {
    val activeChannel = appState.channels.firstOrNull {
        it.id == appState.activeChannelId
    }
    val statusLabel = when {
        isCapturing -> "Recording"
        appState.connection.readyForMonitor -> "Radio connected"
        appState.inputMode == InputMode.OnAPinch -> "Phone ready"
        else -> "Radio setup needed"
    }
    val statusTone = when {
        isCapturing -> TalkcanStatusTone.Recording
        appState.connection.readyForMonitor -> TalkcanStatusTone.Ready
        appState.inputMode == InputMode.OnAPinch -> TalkcanStatusTone.Ready
        else -> TalkcanStatusTone.Attention
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TalkcanBrandLabel()
            TextButton(onClick = onActivityLog) {
                Text("Activity")
            }
        }
        TalkcanStatusBadge(
            label = statusLabel,
            tone = statusTone,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = activeChannel?.name ?: "Choose a channel",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (activeChannel == null) {
                    "Your talk button will route here once a channel is selected."
                } else {
                    "Selected channel · your next recording goes here."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DashboardTalkLevel(
    level: Float,
    isCapturing: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCapturing) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (isCapturing) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        border = BorderStroke(
            if (isCapturing) 2.dp else 1.dp,
            if (isCapturing) CanRed else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TalkcanSectionHeader(
                title = if (isCapturing) "Recording now" else "Talk level",
                supportingText = if (isCapturing) {
                    "Keep your voice in the clear range."
                } else {
                    "Hold the talk button to start."
                },
            )
            VuMeter(level = level, isCapturing = isCapturing)
        }
    }
}

@Composable
private fun InputModeSelector(
    appState: AppState,
    actions: PttUiActions,
) {
    val availability = appState.inputModeAvailability
    val activeMode = appState.inputMode

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TalkcanSectionHeader(
            title = "Talk from",
            supportingText = "Choose which microphone and talk button Talkcan uses.",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeSegment(
                mode = InputMode.Work,
                label = "Radio",
                status = if (availability.work) "Ready" else "Set up",
                isActive = activeMode == InputMode.Work,
                isAvailable = availability.work,
                onTileAction = {
                    action -> handleModeTileAction(action, InputMode.Work, actions)
                },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnTheRoad,
                label = "Car",
                status = if (availability.onTheRoad) "Ready" else "Set up",
                isActive = activeMode == InputMode.OnTheRoad,
                isAvailable = availability.onTheRoad,
                onTileAction = {
                    action -> handleModeTileAction(
                        action,
                        InputMode.OnTheRoad,
                        actions,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnAPinch,
                label = "Phone",
                status = "Ready",
                isActive = activeMode == InputMode.OnAPinch,
                isAvailable = availability.onAPinch,
                onTileAction = {
                    action -> handleModeTileAction(
                        action,
                        InputMode.OnAPinch,
                        actions,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ModeSegment(
    mode: InputMode,
    label: String,
    status: String,
    isActive: Boolean,
    isAvailable: Boolean,
    onTileAction: (DashboardModeTileAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        isActive -> MaterialTheme.colorScheme.primary
        isAvailable -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = if (isAvailable) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(if (isActive) 2.dp else 1.dp, accent),
        modifier = modifier
            .height(112.dp)
            .combinedClickable(
                onClick = {
                    onTileAction(dashboardModeTileTapAction(mode, isAvailable))
                },
                onLongClick = if (mode != InputMode.OnAPinch) {
                    { onTileAction(dashboardModeTileLongPressAction(mode)) }
                } else {
                    null
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ModeGlyph(
                mode = mode,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    contentColor
                },
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (isActive) "Selected" else status,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun handleModeTileAction(
    action: DashboardModeTileAction,
    mode: InputMode,
    actions: PttUiActions,
) = dispatchDashboardModeTileAction(
    action = action,
    mode = mode,
    onModeSelected = actions::setInputMode,
    onRsmSetupRequested = actions::navigateToRsmSetup,
    onCarSetupRequested = actions::navigateToCarSetup,
)

internal fun dispatchDashboardModeTileAction(
    action: DashboardModeTileAction,
    mode: InputMode,
    onModeSelected: (InputMode) -> Unit,
    onRsmSetupRequested: () -> Unit,
    onCarSetupRequested: () -> Unit,
) {
    when (action) {
        DashboardModeTileAction.SelectMode -> onModeSelected(mode)
        DashboardModeTileAction.OpenRsmSetup -> onRsmSetupRequested()
        DashboardModeTileAction.OpenCarSetup -> onCarSetupRequested()
        DashboardModeTileAction.Ignore -> Unit
    }
}

@Composable
private fun ModeGlyph(
    mode: InputMode,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = size.minDimension * 0.075f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        when (mode) {
            InputMode.Work -> {
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.12f),
                    size = Size(w * 0.68f, h * 0.72f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.22f, h * 0.52f), Offset(w * 0.22f, h * 0.76f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.78f, h * 0.52f), Offset(w * 0.78f, h * 0.76f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.78f), Offset(w * 0.78f, h * 0.78f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.78f), Offset(w * 0.58f, h * 0.68f), strokeWidth, StrokeCap.Round)
            }
            InputMode.OnTheRoad -> {
                drawCircle(color = color, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(color = color, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.19f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.25f, h * 0.68f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.75f, h * 0.68f), strokeWidth, StrokeCap.Round)
            }
            InputMode.OnAPinch -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.28f, h * 0.08f),
                    size = Size(w * 0.44f, h * 0.84f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.58f, h * 0.78f), strokeWidth, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ChannelPanel(
    appState: AppState,
    providerDescriptors: List<ChannelImplementationDescriptor>,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    var isManaging by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TalkcanSectionHeader(
            title = "Channels",
            supportingText = if (isManaging) {
                "Rename, reorder, configure, or add a route."
            } else {
                "Tap to select. Hold to talk. Slide while holding to lock."
            },
            actionLabel = if (isManaging) "Done" else "Manage",
            onAction = { isManaging = !isManaging },
        )

        if (isManaging) {
            CatalogueManagementPanel(appState, providerDescriptors, actions)
        } else {
            appState.channels.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    activeChannelId = appState.activeChannelId,
                    descriptor = providerDescriptors.firstOrNull {
                        it.implementationId == channel.implementationId
                    },
                    actions = actions,
                    phonePttGesture = phonePttGesture,
                    phonePttLockThresholdPx = phonePttLockThresholdPx,
                    onPhonePttTransition = onPhonePttTransition,
                )
            }
        }
    }
}

@Composable
private fun CatalogueManagementPanel(
    appState: AppState,
    providerDescriptors: List<ChannelImplementationDescriptor>,
    actions: PttUiActions,
) {
    var newName by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = actions::navigateToPackageManagement,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Manage installed packages")
        }
        OutlinedButton(
            onClick = actions::navigateToVoiceProfiles,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Manage voice profiles")
        }
        appState.channels.forEachIndexed { index, channel ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (renameTargetId == channel.id) {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                label = { Text("Channel name") },
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    if (renameText.isNotBlank()) actions.renameChannel(channel.id, renameText)
                                    renameTargetId = null
                                },
                            ) { Text("Save") }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                val label = providerDescriptors.firstOrNull {
                                    it.implementationId == channel.implementationId
                                }?.presentation?.label ?: channel.implementationId.value
                                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    renameTargetId = channel.id
                                    renameText = channel.name
                                }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                                IconButton(
                                    onClick = { actions.moveChannel(channel.id, index - 1) },
                                    enabled = index > 0,
                                ) { Text("▲") }
                                IconButton(
                                    onClick = { actions.moveChannel(channel.id, index + 1) },
                                    enabled = index < appState.channels.lastIndex,
                                ) { Text("▼") }
                                IconButton(
                                    onClick = { actions.removeChannel(channel.id) },
                                    enabled = appState.channels.size > 1,
                                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                            }
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TalkcanSectionHeader(
                    title = "Add a channel",
                    supportingText = "Name the route, then choose what it connects to.",
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Choose a provider", style = MaterialTheme.typography.bodyMedium)
                providerDescriptors.forEach { descriptor ->
                    OutlinedButton(
                        onClick = {
                            actions.navigateToChannelCreation(descriptor.implementationId, newName)
                        },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(descriptor.presentation.label, fontWeight = FontWeight.SemiBold)
                            Text(descriptor.presentation.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (providerDescriptors.isEmpty()) {
                    Text("No channel providers are currently available.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

internal enum class ChannelCardTone { Primary, Secondary }

internal data class ChannelCardPresentation(
    val statusLabel: String,
    val tone: ChannelCardTone,
)

internal fun channelCardPresentation(
    isActive: Boolean,
    isAvailable: Boolean,
    isPttActive: Boolean,
    isLocked: Boolean,
    playbackPaused: Boolean = false,
): ChannelCardPresentation = ChannelCardPresentation(
    statusLabel = when {
        isLocked -> "Locked"
        isPttActive -> "Recording"
        playbackPaused -> "Paused"
        isActive -> "Selected"
        !isAvailable -> "Unavailable"
        else -> "Ready"
    },
    tone = if (isActive && !isPttActive) ChannelCardTone.Primary else ChannelCardTone.Secondary,
)

internal fun pendingResponseLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 pending response"
    else -> "$count pending responses"
}

@Composable
private fun ChannelCard(
    channel: ChannelRuntimeSnapshot,
    activeChannelId: String?,
    descriptor: ChannelImplementationDescriptor?,
    actions: PttUiActions,
    phonePttGesture: PhonePttGestureState,
    phonePttLockThresholdPx: Float,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val channelId = channel.id
    val isActive = activeChannelId == channelId
    val isImmediatelyAvailable =
        channel.preparation is ChannelPreparationAvailability.Available
    val isPttActive = phonePttGesture.activeChannelId == channelId
    val isLocked = phonePttGesture.isLocked && isPttActive
    val presentation = channelCardPresentation(
        isActive = isActive,
        isAvailable = isImmediatelyAvailable,
        isPttActive = isPttActive,
        isLocked = isLocked,
        playbackPaused = channel.playbackPaused,
    )
    val statusTone = when {
        isLocked || isPttActive -> TalkcanStatusTone.Recording
        !isImmediatelyAvailable -> TalkcanStatusTone.Error
        isActive -> TalkcanStatusTone.Active
        channel.playbackPaused -> TalkcanStatusTone.Neutral
        else -> TalkcanStatusTone.Ready
    }
    val currentSelectChannel by rememberUpdatedState(actions::setActiveChannel)
    val currentPhonePttTransition by rememberUpdatedState(onPhonePttTransition)
    val interactionModifier = Modifier.phonePttInput(
        channelId = channelId,
        lockThresholdPx = phonePttLockThresholdPx,
        onSelect = { currentSelectChannel(it) },
        onPhonePttTransition = { currentPhonePttTransition(it) },
    )
    val availabilityMessage = when (val preparation = channel.preparation) {
        ChannelPreparationAvailability.Available -> null
        is ChannelPreparationAvailability.Recoverable -> preparation.reason.message
        is ChannelPreparationAvailability.Unavailable -> preparation.reason.message
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPttActive -> StringYellow
                isActive -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when {
                isPttActive -> Ink
                isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        ),
        border = BorderStroke(
            if (isActive || isPttActive) 2.dp else 1.dp,
            when {
                isPttActive -> CanRed
                isActive -> MaterialTheme.colorScheme.primary
                !isImmediatelyAvailable -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(interactionModifier),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channel.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(
                        label = presentation.statusLabel,
                        tone = statusTone,
                    )
                }
                Text(
                    text = descriptor?.presentation?.summary
                        ?: channel.implementationId.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                availabilityMessage?.let { reason ->
                    Text(
                        text = "$reason ${
                            descriptor?.presentation?.unavailableMessage.orEmpty()
                        }".trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!isImmediatelyAvailable) {
                    Text(
                        text = "Open channel settings to repair this route.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HeldPhonePttInstruction(channelId, phonePttGesture)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pendingLabel = pendingResponseLabel(channel.pendingCount)
                if (pendingLabel != null) {
                    StatusPill(
                        label = pendingLabel,
                        tone = TalkcanStatusTone.Attention,
                        modifier = Modifier.clickable(
                            role = Role.Button,
                            onClick = { actions.setActiveChannel(channel.id) },
                        ),
                    )
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }
                TextButton(
                    onClick = {
                        actions.navigateToChannelConfiguration(channel.id)
                    },
                    enabled = descriptor != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Settings")
                }
            }

            LockedPhonePttStop(
                channelId,
                phonePttGesture,
                onPhonePttTransition,
            )
        }
    }
}
@Composable
private fun HeldPhonePttInstruction(
    channelId: String,
    phonePttGesture: PhonePttGestureState,
) {
    val armed = phonePttGesture as? PhonePttGestureState.Armed
    if (armed?.channelId != channelId) return
    val direction = when (armed.lockDirection) {
        PhonePttLockDirection.Right -> "Slide right to lock."
        PhonePttLockDirection.Left -> "Slide left to lock."
    }
    Text(
        text = "Recording. $direction",
        style = MaterialTheme.typography.bodyMedium,
        color = CanRed,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun LockedPhonePttStop(
    channelId: String,
    phonePttGesture: PhonePttGestureState,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
) {
    val locked = phonePttGesture as? PhonePttGestureState.Locked
    if (locked?.channelId != channelId) return
    Text(
        text = "Recording is locked on.",
        style = MaterialTheme.typography.bodyMedium,
        color = CanRed,
        fontWeight = FontWeight.Bold,
    )
    Button(
        onClick = {
            onPhonePttTransition(phonePttGesture.stopPhonePttGesture())
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Stop recording")
    }
}




@Composable
private fun StatusPill(
    label: String,
    tone: TalkcanStatusTone,
    modifier: Modifier = Modifier,
) {
    TalkcanStatusBadge(
        label = label,
        tone = tone,
        modifier = modifier,
    )
}



data class DashboardVuMeterState(
    val isPresent: Boolean,
    val level: Float,
)

fun dashboardVuMeterState(isCapturing: Boolean, level: Float): DashboardVuMeterState =
    DashboardVuMeterState(isPresent = true, level = if (isCapturing) level else 0f)

enum class DashboardModeTileAction {
    SelectMode,
    OpenRsmSetup,
    OpenCarSetup,
    Ignore,
}

fun dashboardModeTileTapAction(mode: InputMode, isAvailable: Boolean): DashboardModeTileAction = when {
    isAvailable -> DashboardModeTileAction.SelectMode
    mode == InputMode.Work -> DashboardModeTileAction.OpenRsmSetup
    else -> DashboardModeTileAction.Ignore
}

fun dashboardModeTileLongPressAction(mode: InputMode): DashboardModeTileAction = when (mode) {
    InputMode.Work -> DashboardModeTileAction.OpenRsmSetup
    InputMode.OnTheRoad -> DashboardModeTileAction.OpenCarSetup
    InputMode.OnAPinch -> DashboardModeTileAction.Ignore
}
