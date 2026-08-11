package io.talkcan.ui

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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
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
import io.talkcan.ui.theme.ControlSteel
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DashboardHeader(
                    appState = appState,
                    isCapturing = isCapturing,
                )

                ChannelPanel(
                    appState = appState,
                    providerDescriptors = providerDescriptors,
                    actions = actions,
                )
            }

            DashboardOperationalPanel(
                appState = appState,
                level = level,
                isCapturing = isCapturing,
                actions = actions,
            )

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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TalkcanBrandLabel(compact = true)
        TalkcanStatusBadge(
            label = statusLabel,
            tone = statusTone,
        )
    }
}

@Composable
private fun DashboardOperationalPanel(
    appState: AppState,
    level: Float,
    isCapturing: Boolean,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OPERATIONAL_PANEL_HEIGHT)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("dashboard-operational-panel"),
    ) {
        if (isCapturing) {
            DashboardTalkLevel(
                level = level,
                isCapturing = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            InputModeSelector(
                appState = appState,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DashboardTalkLevel(
    level: Float,
    isCapturing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TalkcanSectionHeader(
            title = "Talk level",
            supportingText = "Recording now · Aim for Clear.",
            compact = true,
        )
        TalkcanInstrumentPanel(
            modifier = Modifier
                .fillMaxWidth()
                .height(OPERATIONAL_CONTENT_HEIGHT)
                .testTag("talk-level-card"),
            borderColor = SignalAmber,
            containerColor = NearBlack,
            contentPadding = 14.dp,
        ) {
            VuMeter(level = level, isCapturing = isCapturing)
        }
    }
}

@Composable
private fun InputModeSelector(
    appState: AppState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    val availability = appState.inputModeAvailability
    val activeMode = appState.inputMode

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TalkcanSectionHeader(
            title = "Audio device",
            supportingText = "Replies use this route; PTT switches automatically.",
            compact = true,
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
                status = if (availability.work) "Ready" else "Unavailable",
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
                status = if (availability.onTheRoad) "Ready" else "Unavailable",
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
                status = if (availability.onAPinch) "Ready" else "Unavailable",
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
        isActive -> StatusCyan
        else -> ControlSteel
    }
    val contentColor = if (isAvailable) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusColor = when {
        isActive -> StatusCyan
        isAvailable -> SignalAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    TalkcanInstrumentPanel(
        modifier = modifier
            .height(OPERATIONAL_CONTENT_HEIGHT)
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeGlyph(
                mode = mode,
                color = if (isActive) StatusCyan else contentColor,
                modifier = Modifier.size(32.dp),
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TalkcanSectionHeader(
            title = "Channels",
            supportingText = "Tap to select.",
            compact = true,
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
        isActive -> TalkcanStatusTone.Selected
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
        isActive -> StatusCyan
        !isImmediatelyAvailable -> MaterialTheme.colorScheme.error
        else -> ControlSteel
    }

    TalkcanInstrumentPanel(
        modifier = Modifier.fillMaxWidth(),
        borderColor = panelBorderColor,
        containerColor = NearBlack,
        contentPadding = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .then(interactionModifier)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channel.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                availabilityMessage?.let { reason ->
                    Text(
                        text = "$reason ${
                            descriptor?.presentation?.unavailableMessage.orEmpty()
                        }".trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = {
                    actions.navigateToChannelConfiguration(channel.id)
                },
                enabled = descriptor != null,
                modifier = Modifier.testTag("channel-settings-${channel.id}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings for ${channel.name}",
                )
            }
        }

        pendingResponseLabel(channel.pendingCount)?.let { pendingLabel ->
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
    val isPttActive =
        activeChannelId != null &&
            phonePttGesture.isActive &&
            phonePttTargetChannelId == activeChannelId
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
            title = "No channel selected"
            subtitle = "Select a channel above"
            containerColor = NearBlack
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            borderColor = ControlSteel
        }
        isPlaybackActive -> {
            title = "Playback active"
            subtitle = "Talk is unavailable while audio plays"
            containerColor = NearBlack
            contentColor = StatusCyan
            borderColor = StatusCyan
        }
        phase == PttAudioOperationPhase.PENDING -> {
            title = "Connecting"
            subtitle = when (source) {
                PttSource.Rsm -> "Preparing radio PTT"
                PttSource.CarTelecom -> "Preparing car PTT"
                else -> "Preparing phone microphone"
            }
            containerColor = NearBlack
            contentColor = StatusCyan
            borderColor = StatusCyan
        }
        phase == PttAudioOperationPhase.RECORDING -> {
            title = when (source) {
                PttSource.Rsm -> "Recording on radio"
                PttSource.CarTelecom -> "Recording in car"
                else -> "Release to send"
            }
            subtitle = when (source) {
                PttSource.Rsm -> "Release radio PTT to send"
                PttSource.CarTelecom -> "Release car PTT to send"
                else -> "Recording for ${activeChannel.name}"
            }
            containerColor = SignalAmber
            contentColor = Graphite
            borderColor = SignalAmber
        }
        phase == PttAudioOperationPhase.FINALIZING -> {
            title = "Sending"
            subtitle = when (source) {
                PttSource.Rsm -> "Finishing radio transmission"
                PttSource.CarTelecom -> "Finishing car transmission"
                else -> "Finishing ${activeChannel.name} transmission"
            }
            containerColor = NearBlack
            contentColor = StatusCyan
            borderColor = StatusCyan
        }
        activeChannel.preparation !is ChannelPreparationAvailability.Available -> {
            title = "Channel unavailable"
            subtitle = "Select another channel above"
            containerColor = NearBlack
            contentColor = MaterialTheme.colorScheme.error
            borderColor = MaterialTheme.colorScheme.error
        }
        isPttActive -> {
            title = "Release to send"
            subtitle = "Recording for ${activeChannel.name}"
            containerColor = SignalAmber
            contentColor = Graphite
            borderColor = SignalAmber
        }
        else -> {
            title = "Hold to talk"
            subtitle = "${activeChannel.name} · Release to send"
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
            .height(120.dp)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = contentColor,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


private val OPERATIONAL_PANEL_HEIGHT = 168.dp
private val OPERATIONAL_CONTENT_HEIGHT = 96.dp
