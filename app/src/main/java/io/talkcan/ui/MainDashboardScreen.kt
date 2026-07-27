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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.talkcan.model.AppState
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.InputMode
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelRuntimeSnapshot

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
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "TALKCAN",
            subtitle = "Field operations console",
            onLongPress = { actions.navigateToLogAnalysis() },
        )

        VuMeter(level = level, isCapturing = isCapturing)

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
private fun InputModeSelector(
    appState: AppState,
    actions: PttUiActions,
) {
    val availability = appState.inputModeAvailability
    val activeMode = appState.inputMode

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "INPUT MODE",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeSegment(
                mode = InputMode.Work,
                label = "RSM",
                status = if (availability.work) "READY" else "SETUP",
                isActive = activeMode == InputMode.Work,
                isAvailable = availability.work,
                onTileAction = { action -> handleModeTileAction(action, InputMode.Work, actions) },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnTheRoad,
                label = "CAR",
                status = if (availability.onTheRoad) "READY" else "OFFLINE",
                isActive = activeMode == InputMode.OnTheRoad,
                isAvailable = availability.onTheRoad,
                onTileAction = { action -> handleModeTileAction(action, InputMode.OnTheRoad, actions) },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnAPinch,
                label = "PHONE",
                status = "READY",
                isActive = activeMode == InputMode.OnAPinch,
                isAvailable = availability.onAPinch,
                onTileAction = { action -> handleModeTileAction(action, InputMode.OnAPinch, actions) },
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
    val accent = if (isActive) MaterialTheme.colorScheme.primary
    else if (isAvailable) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.outline
    val contentColor = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val statusColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isAvailable -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(if (isActive) 2.dp else 1.dp, accent),
        modifier = modifier
            .height(118.dp)
            .combinedClickable(
                onClick = { onTileAction(dashboardModeTileTapAction(mode, isAvailable)) },
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeGlyph(
                mode = mode,
                color = contentColor,
                modifier = Modifier.size(42.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CHANNELS",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = { isManaging = !isManaging }) {
                Icon(
                    imageVector = if (isManaging) Icons.Filled.Close else Icons.Filled.Edit,
                    contentDescription = if (isManaging) "Done" else "Manage Channels",
                )
            }
        }

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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ADD CHANNEL", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
        isLocked -> "LOCKED"
        isPttActive -> "PTT"
        playbackPaused -> "PAUSED"
        isActive -> "ACTIVE"
        !isAvailable -> "UNAVAILABLE"
        else -> "READY"
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
    val isImmediatelyAvailable = channel.preparation is ChannelPreparationAvailability.Available
    val isPttActive = phonePttGesture.activeChannelId == channelId
    val presentation = channelCardPresentation(
        isActive = isActive,
        isAvailable = isImmediatelyAvailable,
        isPttActive = isPttActive,
        isLocked = phonePttGesture.isLocked && isPttActive,
        playbackPaused = channel.playbackPaused,
    )
    val accent = when (presentation.tone) {
        ChannelCardTone.Primary -> MaterialTheme.colorScheme.primary
        ChannelCardTone.Secondary -> MaterialTheme.colorScheme.secondary
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().then(interactionModifier),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        descriptor?.presentation?.summary ?: channel.implementationId.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    availabilityMessage?.let { reason ->
                        Text(
                            text = "$reason ${descriptor?.presentation?.unavailableMessage.orEmpty()}".trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!isImmediatelyAvailable) {
                        Text(
                            text = "Recovery: configure this channel when its provider is available, or remove it from channel management.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HeldPhonePttInstruction(channelId, phonePttGesture)
                }
                LockedPhonePttStop(channelId, phonePttGesture, onPhonePttTransition)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                pendingResponseLabel(channel.pendingCount)?.let { label ->
                    StatusPill(
                        label = label,
                        accent = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable(
                            role = Role.Button,
                            onClick = { actions.setActiveChannel(channel.id) },
                        ),
                    )
                }
                StatusPill(
                    label = presentation.statusLabel,
                    accent = accent,
                )
                IconButton(
                    onClick = { actions.navigateToChannelConfiguration(channel.id) },
                    enabled = descriptor != null,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Configure")
                }
            }
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
        PhonePttLockDirection.Right -> "slide right to lock ▶"
        PhonePttLockDirection.Left -> "◀ slide left to lock"
    }
    Text(
        text = "RECORDING — $direction",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
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
        text = "RECORDING LOCKED",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
    )
    Button(
        onClick = { onPhonePttTransition(phonePttGesture.stopPhonePttGesture()) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("STOP")
    }
}




@Composable
private fun StatusPill(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
