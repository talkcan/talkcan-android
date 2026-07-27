package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.dependency.GitHubPublisherTier
import io.talkcan.dependency.GitHubSourceFailure
import io.talkcan.service.PackageManagementState
import io.talkcan.service.PackageManagementSummary
import io.talkcan.service.InstalledPackageSummary
import io.talkcan.ui.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PackageManagementScreen(
    summary: PackageManagementSummary,
    profileState: io.talkcan.service.GenericProfileManagementState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    val state = summary.state

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "PACKAGE MANAGEMENT",
            subtitle = "Install and manage channel providers",
            onLongPress = {},
        )

        InstallSection(state, actions)

        PublishedProfileTypesSection(profileState, actions)

        InstalledPackagesSection(summary, state, actions)
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Install / resolve / inspect / select / trust
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun InstallSection(
    state: PackageManagementState,
    actions: PttUiActions,
) {
    val isActive = state.isOperationActive

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("INSTALL PROVIDER", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            when (state) {
                is PackageManagementState.Idle,
                is PackageManagementState.Failed -> UrlInputAndResolve(isActive, state, actions)

                is PackageManagementState.ResolvingRepository -> ProgressRow("Resolving repository…")
                is PackageManagementState.LoadingReleases -> ProgressRow("Loading releases…")
                is PackageManagementState.InspectingCandidate -> ProgressRow(
                    "Inspecting release ${state.current} of ${state.total}…"
                )

                is PackageManagementState.AwaitingSelection -> ReleaseSelection(state, actions)
                is PackageManagementState.AwaitingTrust -> TrustConfirmation(state, actions)

                is PackageManagementState.Installing -> ProgressRow("Installing…")
                is PackageManagementState.Updating -> ProgressRow("Updating…")
                is PackageManagementState.Refreshing -> ProgressRow("Refreshing…")
                is PackageManagementState.RollingBack -> ProgressRow("Rolling back…")
                is PackageManagementState.Removing -> ProgressRow("Removing…")
                is PackageManagementState.Ready -> InstallCompleteNotice(actions)
                is PackageManagementState.Closed -> Text(
                    "Package management is unavailable (service closed).",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private data class RecommendedPackage(
    val displayName: String,
    val canonicalUrl: String,
)

// Static, ordered list of official Lua packages (design D9). Only the readable display names
// are shown; the canonical URLs are passed verbatim to resolvePackageRepository, identical to
// typing the URL and pressing Resolve.
private val recommendedPackages = listOf(
    RecommendedPackage("Journal", "https://github.com/talkcan/journal-channel"),
    RecommendedPackage("Keyboard", "https://github.com/talkcan/keyboard-channel"),
    RecommendedPackage("OpenAI Agent", "https://github.com/talkcan/openai-agent-channel"),
    RecommendedPackage("Debug", "https://github.com/talkcan/debug-channel"),
    RecommendedPackage("Diagnostics", "https://github.com/talkcan/diagnostics-channel"),
)

@Composable
private fun RecommendedPackages(
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "RECOMMENDED",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        recommendedPackages.forEach { pkg ->
            TextButton(
                onClick = { onSelect(pkg.canonicalUrl) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(pkg.displayName)
            }
        }
    }
}

@Composable
private fun UrlInputAndResolve(
    isActive: Boolean,
    state: PackageManagementState,
    actions: PttUiActions,
) {
    var url by remember { mutableStateOf("") }

    if (state is PackageManagementState.Failed) {
        TypedFailureDisplay(state.failure, actions)
    }

    RecommendedPackages(
        enabled = !isActive,
        onSelect = { canonicalUrl -> actions.resolvePackageRepository(canonicalUrl) },
    )

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Canonical repository URL") },
        placeholder = { Text("https://github.com/<owner>/<repository>") },
        singleLine = true,
        enabled = !isActive,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { actions.resolvePackageRepository(url.trim()) },
        enabled = !isActive && url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Resolve")
    }
}

@Composable
private fun ReleaseSelection(
    state: PackageManagementState.AwaitingSelection,
    actions: PttUiActions,
) {
    val repo = state.repository
    Text(
        "Repository: ${repo.fullName}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "Repository ID: ${repo.id.value}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )

    if (state.candidates.isNotEmpty()) {
        Text(
            "${state.candidates.size} compatible release(s) found.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.candidates.forEach { candidate ->
            val release = candidate.release
            val asset = candidate.asset
            OutlinedButton(
                onClick = { actions.selectPackageRelease(release.releaseId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Release: ${release.tag}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Published: ${formatEpochSeconds(release.publishedAtEpochSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Asset: ${asset.name} (${formatBytes(asset.size)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Digest: ${candidate.digest.value.take(16)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    if (state.ineligible.isNotEmpty()) {
        Text(
            "${state.ineligible.size} release(s) were incompatible or invalid.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    OutlinedButton(
        onClick = { actions.cancelPackageInspection() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Cancel")
    }
}

@Composable
private fun TrustConfirmation(
    state: PackageManagementState.AwaitingTrust,
    actions: PttUiActions,
) {
    val confirmation = state.confirmation
    val tier = state.tier

    // Acknowledgement resets when repository, release, asset, digest, or
    // operation generation changes. The trustKey captures all identity fields;
    // when the coordinator emits a structurally different AwaitingTrust (new
    // generation), trustKey changes and remember resets acknowledged to false.
    val trustKey = remember(confirmation) {
        "${confirmation.canonicalRepositoryUrl}:" +
            "${confirmation.releaseTag}:" +
            "${confirmation.assetName}:" +
            "${confirmation.inspectedDigest.value}"
    }
    var acknowledged by remember(trustKey) { mutableStateOf(false) }

    Text("CONFIRM INSTALL", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Repository: ${confirmation.canonicalRepositoryUrl}")
        Text("Package: ${confirmation.packageLabel}")
        Text(confirmation.packageSummary, style = MaterialTheme.typography.bodySmall)
        Text("Release: ${confirmation.releaseTag}")
        Text("Published: ${formatEpochSeconds(confirmation.publicationTimeEpochSeconds)}")
        Text("Asset: ${confirmation.assetName} (${formatBytes(confirmation.assetSize)})")
        Text("Digest: ${confirmation.inspectedDigest.value}")
    }

    when (tier) {
        GitHubPublisherTier.OFFICIAL -> {
            Text(
                "Official: published by the verified project owner. " +
                    "This indicates provenance, not review, audit, signing, or defect freedom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        GitHubPublisherTier.COMMUNITY -> {
            Text(
                "Community — Unreviewed: this package is not published by the verified project owner. " +
                    "You are installing trusted code from a third party. " +
                    "Review the source before proceeding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "DECLARED AUTHORITY",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (confirmation.capabilities.isEmpty()) {
            Text("No capabilities declared.", style = MaterialTheme.typography.bodySmall)
        } else {
            confirmation.capabilities.forEach { capability ->
                Text("• $capability", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Profile types:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (confirmation.profileTypes.isEmpty()) {
            Text("No profile types declared.", style = MaterialTheme.typography.bodySmall)
        } else {
            confirmation.profileTypes.forEach { profileType ->
                Text(
                    "• ${profileType.label} (${profileType.typeId})" +
                        if (profileType.declaresSecretFields) " — protected secret fields" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Choice resolvers:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (confirmation.choiceResolvers.isEmpty()) {
            Text("No choice resolvers declared.", style = MaterialTheme.typography.bodySmall)
        } else {
            confirmation.choiceResolvers.forEach { resolver ->
                Text(
                    "• ${resolver.resolverId}" +
                        if (resolver.capabilities.isEmpty()) ""
                        else " — capabilities: ${resolver.capabilities.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Work queues:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (confirmation.workQueues.isEmpty()) {
            Text("No work queues declared.", style = MaterialTheme.typography.bodySmall)
        } else {
            confirmation.workQueues.forEach { queueId ->
                Text("• $queueId", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmation.declaresNetworkHttp && confirmation.declaresSecretsRead) {
        Text(
            "Warning: This package declares both secrets.read and network.http. " +
                "It can read plaintext profile secrets and send them to any HTTPS origin without restriction.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    } else if (confirmation.declaresNetworkHttp) {
        Text(
            "Warning: This package declares network.http. It can send and receive data to arbitrary HTTPS destinations without restriction.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = acknowledged,
            onCheckedChange = { acknowledged = it },
        )
        Text(
            if (tier == GitHubPublisherTier.OFFICIAL)
                "I confirm I want to install this official package."
            else
                "I acknowledge this is unreviewed community code and I want to install it.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { actions.confirmPackageInstall(acknowledged) },
            enabled = acknowledged,
            modifier = Modifier.weight(1f),
        ) {
            Text("Install")
        }
        OutlinedButton(
            onClick = { actions.cancelPackageInspection() },
            modifier = Modifier.weight(1f),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun InstallCompleteNotice(actions: PttUiActions) {
    Text(
        "Package installed successfully. The provider is now available in the catalogue. " +
            "Create a channel instance from the dashboard's channel management panel.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    OutlinedButton(
        onClick = { actions.navigateBack() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Back to dashboard")
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Published profile types (5.2)
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun PublishedProfileTypesSection(
    profileState: io.talkcan.service.GenericProfileManagementState,
    actions: PttUiActions,
) {
    val types = profileState.publishedTypes

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PROFILE TYPES", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            if (types.isEmpty()) {
                Text(
                    "No installed package publishes profile types.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            types.forEach { type ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${type.label} (${type.identity.localTypeId})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Source: ${type.canonicalOwner}/${type.canonicalRepository}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "Repository ID: ${type.identity.repositoryId.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (type.hasSecretFields) {
                        Text(
                            "Declares protected secret fields.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (type.isSchemaIncompatible) {
                        Text(
                            "Some retained profiles are incompatible with the current schema.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { actions.navigateToGenericProfiles() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manage profiles")
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Installed packages list
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun InstalledPackagesSection(
    summary: PackageManagementSummary,
    state: PackageManagementState,
    actions: PttUiActions,
) {
    val isActive = state.isOperationActive
    val packages = summary.installedPackages

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "INSTALLED PACKAGES",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (packages.isEmpty()) {
            Text(
                "No packages installed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        packages.forEach { pkg ->
            InstalledPackageCard(pkg, isActive, state, actions)
        }
    }
}

@Composable
private fun InstalledPackageCard(
    pkg: InstalledPackageSummary,
    isActive: Boolean,
    state: PackageManagementState,
    actions: PttUiActions,
) {
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showRollbackDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${pkg.canonicalOwner}/${pkg.canonicalRepository}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Repository ID: ${pkg.repositoryId.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text("Version: ${pkg.packageVersion}")
            Text("Release: ${pkg.releaseTag}")

            Text(
                when (pkg.trustTier) {
                    GitHubPublisherTier.OFFICIAL -> "Trust: Official"
                    GitHubPublisherTier.COMMUNITY -> "Trust: Community — Unreviewed"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (pkg.trustTier == GitHubPublisherTier.OFFICIAL)
                    MaterialTheme.colorScheme.secondary
                else
                    MaterialTheme.colorScheme.error,
            )

            Text(
                when (pkg.status) {
                    io.talkcan.service.PackageManagementStatus.AVAILABLE ->
                        "Status: Available"
                    io.talkcan.service.PackageManagementStatus.UNAVAILABLE ->
                        "Status: Unavailable" +
                            if (pkg.failureCategory != null) " ($pkg.failureCategory/$pkg.failureDetail)" else ""
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (pkg.hasRollback) {
                Text(
                    "Rollback available: ${pkg.rollbackVersion} (${pkg.rollbackReleaseTag})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.resolvePackageRepository(
                        "https://github.com/${pkg.canonicalOwner}/${pkg.canonicalRepository}"
                    ) },
                    enabled = !isActive,
                ) {
                    Text("Update")
                }
                if (pkg.hasRollback) {
                    OutlinedButton(
                        onClick = { showRollbackDialog = true },
                        enabled = !isActive,
                    ) {
                        Text("Rollback")
                    }
                }
                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    enabled = !isActive,
                ) {
                    Text("Remove")
                }
            }

            if (showRemoveDialog) {
                ConfirmationDialog(
                    title = "Remove package",
                    body = "This will remove the installed provider package. " +
                        "Channel definitions that use this provider will remain preserved " +
                        "but become unavailable until the package is reinstalled.",
                    confirmLabel = "Remove",
                    onConfirm = {
                        actions.removePackage(pkg.repositoryId)
                        showRemoveDialog = false
                    },
                    onDismiss = { showRemoveDialog = false },
                )
            }
            if (showRollbackDialog) {
                ConfirmationDialog(
                    title = "Rollback package",
                    body = "This will roll back to the previous release " +
                        "(${pkg.rollbackVersion}, ${pkg.rollbackReleaseTag}).",
                    confirmLabel = "Rollback",
                    onConfirm = {
                        actions.rollbackPackage(pkg.repositoryId)
                        showRollbackDialog = false
                    },
                    onDismiss = { showRollbackDialog = false },
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Shared UI helpers
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ProgressRow(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TypedFailureDisplay(
    failure: GitHubSourceFailure,
    actions: PttUiActions,
) {
    val message = when (failure) {
        is GitHubSourceFailure.RateLimitExhausted ->
            "GitHub rate limit exhausted. Limit: ${failure.rateLimit.limit}, " +
                "remaining: ${failure.rateLimit.remaining}. " +
                "Resets at ${formatEpochSeconds(failure.rateLimit.resetTimeEpochSeconds)}."
        GitHubSourceFailure.MalformedUrl -> "The URL is malformed. Use the canonical https://github.com/<owner>/<repository> format."
        GitHubSourceFailure.UnsupportedHostPath -> "Unsupported host or path. Only github.com repository URLs are accepted."
        GitHubSourceFailure.InaccessibleRepository -> "The repository is inaccessible."
        GitHubSourceFailure.PrivateRepository -> "The repository is private. Only public repositories are supported."
        GitHubSourceFailure.ArchivedRepository -> "The repository is archived."
        GitHubSourceFailure.DisabledRepository -> "The repository is disabled."
        GitHubSourceFailure.MalformedResponse -> "GitHub returned a malformed response."
        GitHubSourceFailure.BoundsExceeded -> "A response exceeded configured bounds."
        GitHubSourceFailure.NoStableRelease -> "No stable (non-draft, non-prerelease) release was found."
        GitHubSourceFailure.NoCanonicalAsset -> "No canonical asset (talkcan-channel.zip) was found in any release."
        GitHubSourceFailure.IncompatiblePackage -> "No compatible package was found in any stable release."
        GitHubSourceFailure.InvalidPackage -> "An invalid package was encountered."
        GitHubSourceFailure.RedirectFailure -> "A redirect failed."
        GitHubSourceFailure.NetworkError -> "A network error occurred."
        GitHubSourceFailure.Timeout -> "The request timed out."
        GitHubSourceFailure.Cancelled -> "The operation was cancelled."
        GitHubSourceFailure.StaleOperation -> "The operation was superseded by a newer one."
        GitHubSourceFailure.LifecycleClosed -> "Package management is closed (service shutdown)."
        GitHubSourceFailure.StagingError -> "A staging error occurred."
        GitHubSourceFailure.TrustRefused -> "Trust confirmation was refused."
        is GitHubSourceFailure.Format -> "Package format error: ${failure.detail.name}"
        is GitHubSourceFailure.Identity -> "Package identity error: ${failure.detail.name}"
        is GitHubSourceFailure.Compatibility -> "Package compatibility error: ${failure.detail.name}"
        is GitHubSourceFailure.Integrity -> "Package integrity error: ${failure.detail.name}"
        is GitHubSourceFailure.Storage -> "Package storage error: ${failure.detail.name}"
        is GitHubSourceFailure.Mutation -> "Package mutation error: ${failure.detail.name}"
        is GitHubSourceFailure.Rollback -> "Package rollback error: ${failure.detail.name}"
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

private val PackageManagementState.isOperationActive: Boolean
    get() = when (this) {
        is PackageManagementState.ResolvingRepository,
        is PackageManagementState.LoadingReleases,
        is PackageManagementState.InspectingCandidate,
        is PackageManagementState.Installing,
        is PackageManagementState.Updating,
        is PackageManagementState.Refreshing,
        is PackageManagementState.RollingBack,
        is PackageManagementState.Removing,
        is PackageManagementState.Closed -> true
        else -> false
    }

private fun formatEpochSeconds(epochSeconds: Long): String {
    return try {
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (e: Exception) {
        epochSeconds.toString()
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
