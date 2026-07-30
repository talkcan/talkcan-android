package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.talkcan.model.AppState
import io.talkcan.model.PttAudioOperationState
import io.talkcan.model.PttAudioOperationPhase
import io.talkcan.model.PttSource
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.InputMode
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelRuntimeSnapshot
import io.talkcan.ui.theme.Graphite
import io.talkcan.ui.theme.SignalAmber
import io.talkcan.ui.theme.MutedSteel
import io.talkcan.ui.theme.NearBlack
import io.talkcan.ui.theme.StatusCyan
import io.talkcan.ui.theme.WarmAluminum

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
    var phonePttGesture by remember { mutableStateOf<PhonePttGestureState>(PhonePttGestureState.Idle) }
    var phonePttTargetChannelId by remember { mutableStateOf<String?>(null) }

    fun applyPhonePttTransition(transition: PhonePttGestureTransition) {
        phonePttGesture = transition.state
        for (command in transition.commands) {
            when (command) {
                is PhonePttGestureCommand.Press -> {
                    phonePttTargetChannelId = command.channelId
                    actions.phonePttPressed(command.channelId)
                }
                is PhonePttGestureCommand.Release -> {
                    val target = phonePttTargetChannelId
                    phonePttTargetChannelId = null
                    if (target != null) {
                        actions.phonePttReleased(target)
                    }
                }
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

    TalkcanInstrumentBackdrop(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DashboardHeader(
                    appState = appState,
                    isCapturing = isCapturing,
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
                )
            }

            val activeChannel = appState.channels.firstOrNull {
                it.id == appState.activeChannelId
            }
            PhonePttDock(
                activeChannel = activeChannel,
                phonePttGesture = phonePttGesture,
                phonePttTargetChannelId = phonePttTargetChannelId,
                onPhonePttTransition = ::applyPhonePttTransition,
                pttAudioState = appState.pttAudioState,
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    appState: AppState,
    isCapturing: Boolean,
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
    val statusColor = when {
        isCapturing -> SignalAmber
        appState.connection.readyForMonitor -> StatusCyan
        appState.inputMode == InputMode.OnAPinch -> StatusCyan
        else -> WarmAluminum
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TalkcanBrandLabel()
        TalkcanInstrumentPanel(
            modifier = Modifier.fillMaxWidth(),
            borderColor = statusColor,
        ) {
            TalkcanStatusBadge(
                label = statusLabel,
                tone = statusTone,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = activeChannel?.name ?: "Choose a channel",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
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
    TalkcanInstrumentPanel(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isCapturing) {
            SignalAmber
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
    ) {
        TalkcanSectionHeader(
            title = if (isCapturing) "Recording now" else "Talk level",
            supportingText = if (isCapturing) {
                "Keep your voice in the clear range."
            } else {
                "Hold the talk button to start."
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        VuMeter(level = level, isCapturing = isCapturing)
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
            title = "Audio device",
            supportingText =
                "Choose where replies play. Using another talk button switches devices automatically.",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeSegment(
                mode = InputMode.Work,
                label = "Radio",
                status = if (availability.work) "Ready" else "Set up",
                isActive = activeMode == InputMode.Work,
                isAvailable = availability.work,
                onSelected = {
                    actions.setInputMode(InputMode.Work)
                },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnTheRoad,
                label = "Car",
                status = if (availability.onTheRoad) "Ready" else "Set up",
                isActive = activeMode == InputMode.OnTheRoad,
                isAvailable = availability.onTheRoad,
                onSelected = {
                    actions.setInputMode(InputMode.OnTheRoad)
                },
                modifier = Modifier.weight(1f),
            )
            ModeSegment(
                mode = InputMode.OnAPinch,
                label = "Phone",
                status = "Ready",
                isActive = activeMode == InputMode.OnAPinch,
                isAvailable = availability.onAPinch,
                onSelected = {
                    actions.setInputMode(InputMode.OnAPinch)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeSegment(
    mode: InputMode,
    label: String,
    status: String,
    isActive: Boolean,
    isAvailable: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        isActive -> SignalAmber
        isAvailable -> MutedSteel
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = if (isAvailable) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusColor = when {
        isActive -> SignalAmber
        isAvailable -> StatusCyan
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    TalkcanInstrumentPanel(
        modifier = modifier
            .height(112.dp)
            .selectable(
                selected = isActive,
                enabled = isAvailable,
                role = Role.RadioButton,
                onClick = onSelected,
            )
            .semantics {
                stateDescription = if (isActive) {
                    "Selected"
                } else if (!isAvailable) {
                    "Unavailable"
                } else {
                    "Available"
                }
            }
            .testTag("device-tile-${mode.name}"),
        borderColor = accent,
        containerColor = NearBlack,
        contentPadding = 10.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ModeGlyph(
                mode = mode,
                color = if (isActive) SignalAmber else contentColor,
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (isActive) "Selected" else status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Medium,
            )
        }
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
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TalkcanSectionHeader(
            title = "Channels",
            supportingText = "Tap to select.",
        )
        appState.channels.forEach { channel ->
            ChannelCard(
                channel = channel,
                activeChannelId = appState.activeChannelId,
                descriptor = providerDescriptors.firstOrNull {
                    it.implementationId == channel.implementationId
                },
                actions = actions,
            )
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
    playbackPaused: Boolean = false,
): ChannelCardPresentation = ChannelCardPresentation(
    statusLabel = when {
        playbackPaused -> "Paused"
        isActive -> "Selected"
        !isAvailable -> "Unavailable"
        else -> "Ready"
    },
    tone = if (isActive) ChannelCardTone.Primary else ChannelCardTone.Secondary,
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
) {
    val channelId = channel.id
    val isActive = activeChannelId == channelId
    val isImmediatelyAvailable =
        channel.preparation is ChannelPreparationAvailability.Available
    val presentation = channelCardPresentation(
        isActive = isActive,
        isAvailable = isImmediatelyAvailable,
        playbackPaused = channel.playbackPaused,
    )
    val statusTone = when {
        !isImmediatelyAvailable -> TalkcanStatusTone.Error
        isActive -> TalkcanStatusTone.Active
        channel.playbackPaused -> TalkcanStatusTone.Neutral
        else -> TalkcanStatusTone.Ready
    }
    val currentSelectChannel by rememberUpdatedState(actions::setActiveChannel)
    val interactionModifier = Modifier
        .selectable(
            selected = isActive,
            role = Role.RadioButton,
            onClick = { currentSelectChannel(channelId) },
        )
        .testTag("channel-primary-${channel.id}")
    val availabilityMessage = when (val preparation = channel.preparation) {
        ChannelPreparationAvailability.Available -> null
        is ChannelPreparationAvailability.Recoverable -> preparation.reason.message
        is ChannelPreparationAvailability.Unavailable -> preparation.reason.message
    }
    val panelBorderColor = when {
        isActive -> SignalAmber
        !isImmediatelyAvailable -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    TalkcanInstrumentPanel(
        modifier = Modifier.fillMaxWidth(),
        borderColor = panelBorderColor,
        containerColor = NearBlack,
        contentPadding = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
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
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
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
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pendingLabel = pendingResponseLabel(channel.pendingCount)
            if (pendingLabel != null) {
                StatusPill(
                    label = pendingLabel,
                    tone = TalkcanStatusTone.Ready,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClick = { actions.setActiveChannel(channel.id) },
                        )
                        .testTag("channel-pending-${channel.id}"),
                )
            } else {
                Spacer(modifier = Modifier.size(1.dp))
            }
            TextButton(
                onClick = {
                    actions.navigateToChannelConfiguration(channel.id)
                },
                enabled = descriptor != null,
                modifier = Modifier.testTag("channel-settings-${channel.id}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Settings")
            }
        }
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

internal data class PhonePttDockSemantics(
    val contentDescription: String,
    val stateDescription: String,
    val enabled: Boolean,
)

internal fun phonePttDockSemantics(
    activeChannel: ChannelRuntimeSnapshot?,
    phonePttGesture: PhonePttGestureState,
    phonePttTargetChannelId: String?,
    pttAudioState: PttAudioOperationState,
): PhonePttDockSemantics {
    val activeChannelId = activeChannel?.id
    val isPlaybackActive = pttAudioState.isPlaybackActive
    val phase = pttAudioState.phase
    val source = pttAudioState.source

    val isNonPhoneSession = phase != PttAudioOperationPhase.IDLE && source != PttSource.Phone
    val isTouchEnabled = activeChannelId != null && !isNonPhoneSession && !isPlaybackActive

    val contentDescription = if (activeChannel != null) {
        "Talk to ${activeChannel.name}"
    } else {
        "choose-channel"
    }

    val stateDescription = buildString {
        if (activeChannel != null) {
            append("Channel: ${activeChannel.name}")
        } else {
            append("No active channel")
        }

        val stateText = when {
            isPlaybackActive -> "Playback Active"
            phase == PttAudioOperationPhase.PENDING -> {
                val srcName = when (source) {
                    PttSource.Rsm -> "RSM"
                    PttSource.CarTelecom -> "Car"
                    else -> "Phone"
                }
                "$srcName pending"
            }
            phase == PttAudioOperationPhase.RECORDING -> {
                val srcName = when (source) {
                    PttSource.Rsm -> "RSM"
                    PttSource.CarTelecom -> "Car"
                    else -> "Phone"
                }
                "$srcName recording"
            }
            phase == PttAudioOperationPhase.FINALIZING -> {
                val srcName = when (source) {
                    PttSource.Rsm -> "RSM"
                    PttSource.CarTelecom -> "Car"
                    else -> "Phone"
                }
                "$srcName finalizing"
            }
            activeChannel == null -> {
                "No active channel"
            }
            activeChannel.preparation !is ChannelPreparationAvailability.Available -> {
                "Channel Unavailable"
            }
            else -> {
                "Ready"
            }
        }
        append(", $stateText")

        if (!isTouchEnabled) {
            val disabledReason = when {
                activeChannelId == null -> "No active channel"
                isPlaybackActive -> "Playback Active"
                isNonPhoneSession -> {
                    val srcName = when (source) {
                        PttSource.Rsm -> "RSM"
                        PttSource.CarTelecom -> "Car"
                        else -> "external device"
                    }
                    "Session owned by $srcName"
                }
                else -> ""
            }
            if (disabledReason.isNotEmpty()) {
                append(", Disabled: $disabledReason")
            }
        }
    }

    return PhonePttDockSemantics(
        contentDescription = contentDescription,
        stateDescription = stateDescription,
        enabled = isTouchEnabled,
    )
}


@Composable
private fun PhonePttDock(
    activeChannel: ChannelRuntimeSnapshot?,
    phonePttGesture: PhonePttGestureState,
    phonePttTargetChannelId: String?,
    onPhonePttTransition: (PhonePttGestureTransition) -> Unit,
    pttAudioState: PttAudioOperationState,
    modifier: Modifier = Modifier,
) {
    val activeChannelId = activeChannel?.id
    val currentPhonePttGesture by rememberUpdatedState(phonePttGesture)
    val isPlaybackActive = pttAudioState.isPlaybackActive
    val phase = pttAudioState.phase
    val source = pttAudioState.source
    val isNonPhoneSession =
        phase != PttAudioOperationPhase.IDLE && source != PttSource.Phone
    val isTouchEnabled =
        activeChannelId != null && !isNonPhoneSession && !isPlaybackActive

    val title: String
    val subtitle: String
    val containerColor: Color
    val contentColor: Color
    val borderColor: Color

    when {
        activeChannelId == null -> {
            title = "No active channel"
            subtitle = "Select a channel to route audio"
            containerColor = NearBlack
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            borderColor = MaterialTheme.colorScheme.outlineVariant
        }
        isPlaybackActive -> {
            title = "Playback Active"
            subtitle = "Audio is playing"
            containerColor = NearBlack
            contentColor = StatusCyan
            borderColor = StatusCyan
        }
        phase == PttAudioOperationPhase.PENDING -> {
            title = "Connecting..."
            subtitle = when (source) {
                PttSource.Rsm -> "Preparing Radio..."
                PttSource.CarTelecom -> "Preparing Car..."
                else -> "Preparing Phone Mic..."
            }
            containerColor = NearBlack
            contentColor = StatusCyan
            borderColor = StatusCyan
        }
        phase == PttAudioOperationPhase.RECORDING -> {
            title = when (source) {
                PttSource.Rsm -> "Recording on Radio"
                PttSource.CarTelecom -> "Recording on Car"
                else -> "Recording..."
            }
            subtitle = if (source == PttSource.Phone) {
                "Talk to ${activeChannel.name}"
            } else {
                "Transmission active"
            }
            containerColor = SignalAmber
            contentColor = Graphite
            borderColor = SignalAmber
        }
        phase == PttAudioOperationPhase.FINALIZING -> {
            title = when (source) {
                PttSource.Rsm -> "Finalizing on Radio..."
                PttSource.CarTelecom -> "Finalizing on Car..."
                else -> "Finalizing..."
            }
            subtitle = "Processing audio"
            containerColor = NearBlack
            contentColor = StatusCyan
            borderColor = StatusCyan
        }
        activeChannel.preparation !is ChannelPreparationAvailability.Available -> {
            title = "Channel Unavailable"
            subtitle = "Talk to ${activeChannel.name} (unavailable)"
            containerColor = NearBlack
            contentColor = MaterialTheme.colorScheme.error
            borderColor = MaterialTheme.colorScheme.error
        }
        else -> {
            title = "Hold to Talk"
            subtitle = "Talk to ${activeChannel.name}"
            containerColor = NearBlack
            contentColor = WarmAluminum
            borderColor = SignalAmber
        }
    }

    val currentPhonePttTransition by rememberUpdatedState(onPhonePttTransition)
    val buttonModifier = activeChannelId
        ?.takeIf { isTouchEnabled }
        ?.let { channelId ->
            Modifier.phonePttInput(
                channelId = channelId,
                stateProvider = { currentPhonePttGesture },
                onPhonePttTransition = { currentPhonePttTransition(it) },
            )
        }
        ?: Modifier
    val semanticsProjection = phonePttDockSemantics(
        activeChannel = activeChannel,
        phonePttGesture = phonePttGesture,
        phonePttTargetChannelId = phonePttTargetChannelId,
        pttAudioState = pttAudioState,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Graphite)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("phone-ptt-dock"),
    ) {
        TalkcanInstrumentPanel(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .then(buttonModifier)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = semanticsProjection.contentDescription
                    stateDescription = semanticsProjection.stateDescription
                    if (!semanticsProjection.enabled) {
                        disabled()
                    }
                }
                .focusable(),
            borderColor = borderColor,
            containerColor = containerColor,
            contentPadding = 16.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

