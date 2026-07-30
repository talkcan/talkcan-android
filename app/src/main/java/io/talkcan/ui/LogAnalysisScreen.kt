package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.talkcan.service.LogEntry
import io.talkcan.service.LogLevel
import io.talkcan.service.TalkcanLogger

enum class LogFormatMode { Compact, Detailed }

private val LEVEL_COLORS = mapOf(
    LogLevel.Verbose to Color(0xFF9E9E9E),
    LogLevel.Debug to Color(0xFF4CAF50),
    LogLevel.Info to Color(0xFF2196F3),
    LogLevel.Warn to Color(0xFFFF9800),
    LogLevel.Error to Color(0xFFEF5350),
)

private fun levelColor(level: LogLevel): Color =
    LEVEL_COLORS[level] ?: Color.Gray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogAnalysisScreen(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    onSetGlobalLevel: (LogLevel) -> Unit,
    onSetTagLevel: (String, LogLevel) -> Unit,
    onClearTagLevel: (String) -> Unit,
    currentGlobalLevel: LogLevel,
    tagLevels: Map<String, LogLevel>,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevels by remember { mutableStateOf(setOf<LogLevel>()) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var tagDropdownExpanded by remember { mutableStateOf(false) }
    var fontSizeScale by remember { mutableStateOf(1.0f) }
    var formatMode by remember { mutableStateOf(LogFormatMode.Detailed) }
    var levelConfigExpanded by remember { mutableStateOf(false) }

    val allTags = remember(entries) {
        entries.map { it.tag }.distinct().sorted()
    }

    val filteredEntries = remember(entries, searchQuery, selectedLevels, selectedTag) {
        filterLogEntries(entries, searchQuery, selectedLevels, selectedTag)
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filteredEntries.size) {
        if (filteredEntries.isNotEmpty()) {
            listState.animateScrollToItem(filteredEntries.lastIndex)
        }
    }

    val monoSpacedFontSize = (11 * fontSizeScale).sp
    val tagFontSize = (10 * fontSizeScale).sp
    val timestampFontSize = (9 * fontSizeScale).sp

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "Activity log",
            subtitle = "Browse, filter, and configure diagnostic logs from the running service.",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { fontSizeScale = (fontSizeScale - 0.1f).coerceIn(0.5f, 2.0f) }) {
                    Text("A-")
                }
                TextButton(onClick = { fontSizeScale = (fontSizeScale + 0.1f).coerceIn(0.5f, 2.0f) }) {
                    Text("A+")
                }
                TextButton(onClick = {
                    formatMode = if (formatMode == LogFormatMode.Compact) LogFormatMode.Detailed else LogFormatMode.Compact
                }) {
                    Text(
                        text = if (formatMode == LogFormatMode.Compact) "Detailed" else "Compact",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Clear, contentDescription = "Clear logs")
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search messages") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Filter by level",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogLevel.entries.forEach { level ->
                        FilterChip(
                            selected = level in selectedLevels,
                            onClick = {
                                selectedLevels = if (level in selectedLevels) {
                                    selectedLevels - level
                                } else {
                                    selectedLevels + level
                                }
                            },
                            label = { Text(level.label, fontSize = tagFontSize) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = if (level in selectedLevels) Color.White else levelColor(level),
                            ),
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { tagDropdownExpanded = true }) {
                            Text(selectedTag ?: "All tags")
                        }
                        DropdownMenu(
                            expanded = tagDropdownExpanded,
                            onDismissRequest = { tagDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("All tags") },
                                onClick = {
                                    selectedTag = null
                                    tagDropdownExpanded = false
                                },
                            )
                            allTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag) },
                                    onClick = {
                                        selectedTag = tag
                                        tagDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    TextButton(onClick = { levelConfigExpanded = !levelConfigExpanded }) {
                        Text(if (levelConfigExpanded) "Hide level config" else "Configure levels")
                    }
                }
            }
        }

        if (levelConfigExpanded) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Global level: ${currentGlobalLevel.label}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LogLevel.entries.forEach { level ->
                            AssistChip(
                                onClick = { onSetGlobalLevel(level) },
                                label = { Text(level.label, fontSize = tagFontSize) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (level == currentGlobalLevel) levelColor(level) else Color.Transparent,
                                    labelColor = if (level == currentGlobalLevel) Color.White else levelColor(level),
                                ),
                            )
                        }
                    }

                    HorizontalDivider()

                    if (allTags.isNotEmpty()) {
                        Text(
                            "Per-tag overrides",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        allTags.forEach { tag ->
                            val tagLevel = tagLevels[tag]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    LogLevel.entries.forEach { level ->
                                        AssistChip(
                                            onClick = { onSetTagLevel(tag, level) },
                                            label = { Text(level.label, fontSize = (8 * fontSizeScale).sp) },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = if (level == tagLevel) levelColor(level) else Color.Transparent,
                                                labelColor = if (level == tagLevel) Color.White else levelColor(level),
                                            ),
                                        )
                                    }
                                    if (tagLevel != null) {
                                        TextButton(onClick = { onClearTagLevel(tag) }) {
                                            Text("×", fontSize = (10 * fontSizeScale).sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        TalkcanStatusBadge(
            label = "${filteredEntries.size} entries",
            tone = if (filteredEntries.isEmpty()) TalkcanStatusTone.Neutral else TalkcanStatusTone.Active,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredEntries) { entry ->
                LogEntryRow(
                    entry = entry,
                    formatMode = formatMode,
                    fontSize = monoSpacedFontSize,
                    tagFontSize = tagFontSize,
                    timestampFontSize = timestampFontSize,
                )
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: LogEntry,
    formatMode: LogFormatMode,
    fontSize: androidx.compose.ui.unit.TextUnit,
    tagFontSize: androidx.compose.ui.unit.TextUnit,
    timestampFontSize: androidx.compose.ui.unit.TextUnit,
) {
    val color = levelColor(entry.level)

    if (formatMode == LogFormatMode.Compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.level.label,
                color = color,
                fontSize = tagFontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = entry.message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = entry.level.label,
                        color = color,
                        fontSize = tagFontSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = entry.tag,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = tagFontSize,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = TalkcanLogger.formatTimestamp(entry.timestamp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = timestampFontSize,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = entry.message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
            )
            entry.throwable?.let { throwable ->
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                Text(
                    text = sw.toString(),
                    color = color.copy(alpha = 0.7f),
                    fontSize = timestampFontSize,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

internal fun filterLogEntries(
    entries: List<LogEntry>,
    searchQuery: String,
    selectedLevels: Set<LogLevel>,
    selectedTag: String?,
): List<LogEntry> = entries.filter { entry ->
    val levelOk = selectedLevels.isEmpty() || entry.level in selectedLevels
    val tagOk = selectedTag == null || entry.tag == selectedTag
    val searchOk = searchQuery.isEmpty() ||
        entry.message.contains(searchQuery, ignoreCase = true) ||
        entry.tag.contains(searchQuery, ignoreCase = true)
    levelOk && tagOk && searchOk
}