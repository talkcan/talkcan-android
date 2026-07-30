package io.talkcan.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Typed reason a profile mutation was refused. */
internal sealed interface VoiceProfileFailure {
    val diagnostic: String

    data class DuplicateName(val displayName: String) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "A voice profile named '$displayName' already exists (names are unique case-insensitively)"
    }

    data class ProfileLimitReached(val max: Int) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Custom voice profile limit of $max reached"
    }

    data class AggregateLimitReached(val maxBytes: Long) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Aggregate voice profile storage limit of $maxBytes bytes reached"
    }

    data class ImportTooLarge(val maxBytes: Int) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Imported voice profile document exceeds $maxBytes bytes"
    }

    data class IncompatibleModel(val declared: String, val current: String) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Imported profile declares model '$declared' but the current model is '$current'"
    }

    data class CorruptDocument(val detail: String) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Voice profile document is not a valid Supertonic-3 profile: $detail"
    }

    data class ProfileAssigned(
        val id: VoiceProfileId,
        val dependentChannels: List<VoiceProfileChannelDependency>,
    ) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Voice profile '${id.value}' is assigned to " +
                dependentChannels.joinToString(", ") { it.channelDisplayName } +
                " and cannot be deleted"
    }

    data class UnknownProfile(val id: VoiceProfileId) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "No voice profile with id '${id.value}'"
    }

    data class Storage(val detail: String) : VoiceProfileFailure {
        override val diagnostic: String
            get() = "Voice profile storage error: $detail"
    }
}

internal sealed interface VoiceProfileMutation {
    data class Saved(val summary: VoiceProfileSummary) : VoiceProfileMutation
    data class Deleted(val id: VoiceProfileId) : VoiceProfileMutation
    data class Failed(val failure: VoiceProfileFailure) : VoiceProfileMutation
}

/** Immutable published catalogue: read-only verified built-ins plus custom records. */
internal data class VoiceProfileCatalogue(
    val builtIn: List<VoiceProfileSummary>,
    val custom: List<VoiceProfileSummary>,
) {
    val all: List<VoiceProfileSummary>
        get() = builtIn + custom

    fun summaryFor(id: VoiceProfileId): VoiceProfileSummary? = all.find { it.id == id }

    /** Available compatible profiles eligible as mix sources. */
    val selectable: List<VoiceProfileSummary>
        get() = all.filter { it.selectable }
}

/**
 * Service-owned voice profile repository.
 *
 * Publishes one [StateFlow] containing the ten verified read-only F1-F5/M1-M5 built-ins
 * (virtual records backed by the hash-verified model assets) and every custom profile as an
 * available or typed-unavailable summary. Custom profiles live in a dedicated directory
 * outside `supertonic-3`; their documents and metadata index commit atomically through
 * [VoiceProfileStore]. Startup reconciles on-disk state into typed missing/corrupt/
 * incompatible diagnostics; successful mutations rebuild the catalogue in-memory from the
 * just-committed index and publish immediately.
 */
internal class VoiceProfileRepository(
    private val store: VoiceProfileStore,
    private val builtInDir: File,
    private val currentModel: VoiceProfileModelMetadata,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val assignedChannels: (VoiceProfileId) -> List<VoiceProfileChannelDependency> = { emptyList() },
    private val aggregateLimitBytes: Long = VoiceProfileLimits.MAX_AGGREGATE_STORAGE_BYTES,
) {
    private val mutationLock = Any()
    private val builtInTokens = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

    // Model assets can arrive after service construction during first-run setup.
    private var builtInSummaries: List<VoiceProfileSummary> = resolveBuiltInSummaries()

    private val _catalogue = MutableStateFlow(reconciledCatalogue())

    /** Single published catalogue of built-in and custom profiles. */
    val catalogue: StateFlow<VoiceProfileCatalogue>
        get() = _catalogue.asStateFlow()

    val currentCatalogue: VoiceProfileCatalogue
        get() = _catalogue.value

    /**
     * Re-reads on-disk state and republishes typed missing/corrupt/incompatible diagnostics.
     * Intended for startup and for recovery after an externally interrupted commit.
     */
    fun reload() {
        synchronized(mutationLock) {
            builtInSummaries = resolveBuiltInSummaries()
            _catalogue.value = reconciledCatalogue()
        }
    }

    /**
     * Materializes the complete tensors for any available profile, built-in or custom.
     * Returns null when the profile is unknown or its document cannot be decoded.
     */
    fun loadTensors(id: VoiceProfileId): VoiceProfileTensors? {
        builtInFile(id)?.let { file ->
            return try {
                VoiceProfileStyleDecoder.load(file)
            } catch (_: Exception) {
                null
            }
        }
        return store.readProfileDocument(id)?.tensors
    }

    /**
     * Synthesis-only resolution of a profile's stable on-disk style document path.
     * Returns an absolute path only when the profile is known, available, and compatible
     * in the current catalogue and its document exists on disk; otherwise null. Because
     * the current catalogue is consulted on every call, a profile that becomes missing,
     * corrupt, or incompatible stops resolving on the next request.
     *
     * Host policy only: this path crosses into the service-owned synthesizer and never
     * enters UI state, action results, or Lua configuration.
     */
    fun synthesisStyleFilePath(id: VoiceProfileId): String? {
        val summary = currentCatalogue.summaryFor(id) ?: return null
        if (!summary.selectable) return null
        val file = builtInFile(id) ?: store.profileFile(id)
        if (!file.isFile) return null
        return file.absolutePath
    }

    /**
     * Saves a materialized draft as a brand-new custom profile under a freshly allocated
     * stable ID. Saving always allocates a new ID — even when a custom profile is among the
     * sources — so provenance never becomes self-referential.
     */
    fun saveAsNew(
        tensors: VoiceProfileTensors,
        provenance: VoiceProfileProvenance,
        displayName: String,
    ): VoiceProfileMutation = synchronized(mutationLock) {
        val kind =
            if (provenance.operations.isNotEmpty()) VoiceProfileKind.EDITED else VoiceProfileKind.MIXED
        commitNewProfile(tensors, provenance, displayName, kind, currentModel)
    }

    /** Renames a custom profile. Identity, tensors, assignments, and descendants are unchanged. */
    fun rename(id: VoiceProfileId, newDisplayName: String): VoiceProfileMutation = synchronized(mutationLock) {
        requireValidVoiceProfileDisplayName(newDisplayName)
        val index = store.readIndex()
            ?: return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        val existing = index.entries.find { it.id == id }
            ?: return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        if (existing.displayName == newDisplayName) {
            return@synchronized VoiceProfileMutation.Saved(summaryForEntry(existing))
        }
        if (index.entries.any { it.id != id && it.displayName.equals(newDisplayName, ignoreCase = true) }) {
            return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.DuplicateName(newDisplayName))
        }
        val updated = index.entries.map { if (it.id == id) it.copy(displayName = newDisplayName) else it }
        try {
            store.writeIndex(VoiceProfileIndex(index.version, updated))
        } catch (e: Exception) {
            return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.Storage(e.message ?: "index write failed"))
        }
        publishFromIndex(VoiceProfileIndex(index.version, updated))
        VoiceProfileMutation.Saved(summaryForEntry(existing.copy(displayName = newDisplayName)))
    }

    /**
     * Deletes a custom profile only when no channel is assigned to it. Materialized
     * descendants retain their own complete tensors and historical provenance, so a
     * provenance reference alone never blocks deletion.
     */
    fun delete(id: VoiceProfileId): VoiceProfileMutation = synchronized(mutationLock) {
        val index = store.readIndex()
            ?: return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        if (index.entries.none { it.id == id }) {
            return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        }
        val dependents = assignedChannels(id)
        if (dependents.isNotEmpty()) {
            return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.ProfileAssigned(id, dependents))
        }
        try {
            store.deleteProfileDocument(id)
            store.writeIndex(VoiceProfileIndex(index.version, index.entries.filter { it.id != id }))
        } catch (e: Exception) {
            return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.Storage(e.message ?: "delete failed"))
        }
        publishFromIndex(VoiceProfileIndex(index.version, index.entries.filter { it.id != id }))
        VoiceProfileMutation.Deleted(id)
    }

    /**
     * Imports a profile document from a bounded stream. Enforces the 2 MiB document bound
     * before parsing, rejects flat/malformed/shape-invalid data, and rejects a declared
     * model family/version that does not match the current Supertonic-3 model set. An
     * untagged but otherwise valid import is stored compatibility-unverified.
     */
    fun importFromStream(input: InputStream, displayName: String): VoiceProfileMutation = synchronized(mutationLock) {
        val bounded =
            try {
                readBounded(input, VoiceProfileLimits.MAX_IMPORT_DOCUMENT_BYTES)
            } catch (e: Exception) {
                return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.Storage(e.message ?: "import read failed"))
            } ?: return@synchronized VoiceProfileMutation.Failed(
            VoiceProfileFailure.ImportTooLarge(VoiceProfileLimits.MAX_IMPORT_DOCUMENT_BYTES)
        )
        val decoded =
            try {
                VoiceProfileCodec.decode(String(bounded, Charsets.UTF_8))
            } catch (e: Exception) {
                return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.CorruptDocument(e.message ?: "decode failed"))
            }
        // A declared mismatch is rejected outright; an absent tag yields an unverified
        // profile that downstream channel assignment must acknowledge before use.
        val declared = decoded.model
        val storedModel: VoiceProfileModelMetadata? =
            if (declared == null) {
                null
            } else if (declared == currentModel) {
                currentModel
            } else {
                return@synchronized VoiceProfileMutation.Failed(
                    VoiceProfileFailure.IncompatibleModel(
                        declared = "${declared.family}/${declared.version}",
                        current = "${currentModel.family}/${currentModel.version}",
                    )
                )
            }
        commitNewProfile(decoded.tensors, decoded.provenance, displayName, VoiceProfileKind.IMPORTED, storedModel)
    }

    /**
     * Exports any available profile as canonical nested JSON to the given stream. Output is
     * always re-encoded through [VoiceProfileCodec], so internal formatting never leaks and
     * no internal path is exposed.
     */
    fun exportToStream(id: VoiceProfileId, output: OutputStream): VoiceProfileMutation = synchronized(mutationLock) {
        val summary = currentCatalogue.summaryFor(id)
            ?: return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        if (summary.availability !is VoiceProfileAvailability.Available) {
            return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        }
        val tensors = loadTensors(id)
            ?: return@synchronized VoiceProfileMutation.Failed(VoiceProfileFailure.UnknownProfile(id))
        val provenance = store.readProfileDocument(id)?.provenance
        val json = VoiceProfileCodec.encode(tensors, currentModel, provenance)
        return try {
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
            VoiceProfileMutation.Saved(summary)
        } catch (e: Exception) {
            VoiceProfileMutation.Failed(VoiceProfileFailure.Storage(e.message ?: "export write failed"))
        }
    }

    private fun commitNewProfile(
        tensors: VoiceProfileTensors,
        provenance: VoiceProfileProvenance?,
        displayName: String,
        kind: VoiceProfileKind,
        model: VoiceProfileModelMetadata?,
    ): VoiceProfileMutation {
        requireValidVoiceProfileDisplayName(displayName)
        val index = store.readIndex() ?: VoiceProfileIndex(VoiceProfileLimits.STORE_VERSION, emptyList())
        if (index.entries.size >= VoiceProfileLimits.MAX_CUSTOM_PROFILES) {
            return VoiceProfileMutation.Failed(VoiceProfileFailure.ProfileLimitReached(VoiceProfileLimits.MAX_CUSTOM_PROFILES))
        }
        if (index.entries.any { it.displayName.equals(displayName, ignoreCase = true) }) {
            return VoiceProfileMutation.Failed(VoiceProfileFailure.DuplicateName(displayName))
        }
        val id = VoiceProfileId(newId())
        val json = VoiceProfileCodec.encode(tensors, model, provenance)
        val encodedBytes = json.toByteArray(Charsets.UTF_8).size.toLong()
        if (store.aggregateDocumentBytes() + encodedBytes > aggregateLimitBytes) {
            return VoiceProfileMutation.Failed(VoiceProfileFailure.AggregateLimitReached(aggregateLimitBytes))
        }
        val entry = VoiceProfileIndexEntry(
            id = id,
            displayName = displayName,
            kind = kind,
            modelFamily = model?.family ?: "",
            modelVersion = model?.version ?: "",
        )
        val nextIndex = VoiceProfileIndex(index.version, index.entries + entry)
        try {
            // Document commits first; the index references it only once it is durable.
            store.writeProfileDocument(id, json)
            store.writeIndex(nextIndex)
        } catch (e: Exception) {
            // Best-effort removal of a document whose index entry never committed.
            runCatching { store.deleteProfileDocument(id) }
            return VoiceProfileMutation.Failed(VoiceProfileFailure.Storage(e.message ?: "commit failed"))
        }
        publishFromIndex(nextIndex)
        return VoiceProfileMutation.Saved(summaryForEntry(entry))
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun reconciledCatalogue(): VoiceProfileCatalogue =
        VoiceProfileCatalogue(
            builtIn = builtInSummaries,
            custom = store.reconcile(currentModel).map { customSummary(it) },
        )

    private fun publishFromIndex(index: VoiceProfileIndex) {
        _catalogue.value = VoiceProfileCatalogue(
            builtIn = builtInSummaries,
            custom = index.entries.map { summaryForEntry(it) },
        )
    }

    private fun resolveBuiltInSummaries(): List<VoiceProfileSummary> =
        builtInTokens.map { builtInSummary(it) }

    private fun builtInSummary(token: String): VoiceProfileSummary {
        val id = VoiceProfileId("$BUILTIN_ID_PREFIX$token")
        val file = File(builtInDir, "$token.json")
        val availability =
            if (file.isFile) {
                try {
                    VoiceProfileStyleDecoder.load(file)
                    VoiceProfileAvailability.Available
                } catch (e: Exception) {
                    VoiceProfileAvailability.Unavailable(
                        VoiceProfileUnavailableReason.CORRUPT_DOCUMENT,
                        "Built-in voice profile $token is corrupt: ${e.message}",
                    )
                }
            } else {
                VoiceProfileAvailability.Unavailable(
                    VoiceProfileUnavailableReason.MISSING_FILE,
                    "Built-in voice profile $token is missing",
                )
            }
        return VoiceProfileSummary(
            id = id,
            displayName = token,
            kind = VoiceProfileKind.BUILT_IN,
            availability = availability,
            compatibility = VoiceProfileCompatibility.VERIFIED,
            readOnly = true,
        )
    }

    private fun builtInFile(id: VoiceProfileId): File? {
        if (!id.value.startsWith(BUILTIN_ID_PREFIX)) return null
        val token = id.value.removePrefix(BUILTIN_ID_PREFIX)
        if (token !in builtInTokens) return null
        return File(builtInDir, "$token.json")
    }

    private fun customSummary(record: VoiceProfileRecordState): VoiceProfileSummary =
        when (record) {
            is VoiceProfileRecordState.Available -> summaryForEntry(record.entry)
            is VoiceProfileRecordState.Unverified -> summaryForEntry(record.entry)
            is VoiceProfileRecordState.Unavailable ->
                VoiceProfileSummary(
                    id = record.entry.id,
                    displayName = record.entry.displayName,
                    kind = record.entry.kind,
                    availability = VoiceProfileAvailability.Unavailable(record.reason, record.diagnostic),
                    compatibility =
                        if (record.reason == VoiceProfileUnavailableReason.INCOMPATIBLE_MODEL) {
                            VoiceProfileCompatibility.INCOMPATIBLE
                        } else {
                            VoiceProfileCompatibility.VERIFIED
                        },
                    readOnly = false,
                )
        }

    /** Indexed entries are committed documents; compatibility follows the stored model tag. */
    private fun summaryForEntry(entry: VoiceProfileIndexEntry): VoiceProfileSummary =
        VoiceProfileSummary(
            id = entry.id,
            displayName = entry.displayName,
            kind = entry.kind,
            availability = VoiceProfileAvailability.Available,
            compatibility =
                if (entry.isTagged) VoiceProfileCompatibility.VERIFIED else VoiceProfileCompatibility.UNVERIFIED,
            readOnly = false,
        )

    companion object {
        const val BUILTIN_ID_PREFIX: String = "builtin:"
    }
}
