package io.talkcan.voice

import io.talkcan.audio.onnx.SupertonicStyleException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** One durable custom-profile record in the versioned metadata index. */
internal data class VoiceProfileIndexEntry(
    val id: VoiceProfileId,
    val displayName: String,
    val kind: VoiceProfileKind,
    val modelFamily: String,
    val modelVersion: String,
) {
    init {
        requireValidVoiceProfileDisplayName(displayName)
    }

    /** Filename derives only from the stable ID, never from the display name. */
    val fileName: String
        get() = "${id.value}.json"

    /** Untagged imports carry a blank model version until acknowledged. */
    val isTagged: Boolean
        get() = modelFamily.isNotBlank() && modelVersion.isNotBlank()
}

/** Versioned index of all custom profiles, atomically persisted. */
internal data class VoiceProfileIndex(
    val version: Int,
    val entries: List<VoiceProfileIndexEntry>,
) {
    init {
        require(version == VoiceProfileLimits.STORE_VERSION) {
            "Voice profile index version must be ${VoiceProfileLimits.STORE_VERSION}, found $version"
        }
        val ids = entries.map { it.id }
        require(ids.toSet().size == ids.size) { "Voice profile index IDs must be distinct" }
    }
}

/** Fault injection boundaries for exercising interrupted-commit recovery in tests. */
internal enum class VoiceStoreBoundary {
    PROFILE_TEMP_WRITE,
    PROFILE_TEMP_FSYNC,
    PROFILE_RENAME,
    INDEX_TEMP_WRITE,
    INDEX_TEMP_FSYNC,
    INDEX_RENAME,
}

internal fun interface VoiceStoreFaultInjector {
    fun inject(boundary: VoiceStoreBoundary)

    companion object {
        val NONE: VoiceStoreFaultInjector = VoiceStoreFaultInjector { }
    }
}

/** Typed result of reconciling one index entry against on-disk state at startup. */
internal sealed interface VoiceProfileRecordState {
    val entry: VoiceProfileIndexEntry

    /** Profile file present, decodable, and compatible with the current model set. */
    data class Available(override val entry: VoiceProfileIndexEntry) : VoiceProfileRecordState

    /** Profile file present and decodable but not verified against the current model set. */
    data class Unverified(override val entry: VoiceProfileIndexEntry) : VoiceProfileRecordState

    /** Index entry retained for diagnostics but not selectable. */
    data class Unavailable(
        override val entry: VoiceProfileIndexEntry,
        val reason: VoiceProfileUnavailableReason,
        val diagnostic: String,
    ) : VoiceProfileRecordState
}

/**
 * Atomic custom-profile store under a dedicated directory outside `supertonic-3`.
 *
 * Profile documents are written to stable-ID filenames via a temp file, fsync, and
 * atomic rename; the versioned metadata index commits the same way only after the
 * profile document is durable. An interrupted commit therefore never publishes a
 * partial profile: either the index references a complete document, or the orphaned
 * temp/document is ignored and swept on the next reconciliation.
 */
internal class VoiceProfileStore(
    val storeDir: File,
    private val faultInjector: VoiceStoreFaultInjector = VoiceStoreFaultInjector.NONE,
) {
    private val indexFile: File
        get() = File(storeDir, INDEX_FILE_NAME)

    /** Ensures the dedicated store directory exists. */
    fun ensureDirectory() {
        if (!storeDir.exists() && !storeDir.mkdirs()) {
            throw IOException("Could not create voice profile store directory: ${storeDir.path}")
        }
    }

    fun profileFile(id: VoiceProfileId): File = File(storeDir, "${id.value}.json")

    /**
     * Atomically persists a complete profile document under its stable-ID filename.
     * The document is fully written and fsynced to a temp file before the atomic
     * rename, so readers never observe a partial document.
     */
    fun writeProfileDocument(id: VoiceProfileId, json: String) {
        ensureDirectory()
        val target = profileFile(id)
        val temp = File(storeDir, "${id.value}.$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                faultInjector.inject(VoiceStoreBoundary.PROFILE_TEMP_WRITE)
                out.write(json.toByteArray(Charsets.UTF_8))
                faultInjector.inject(VoiceStoreBoundary.PROFILE_TEMP_FSYNC)
                out.fd.sync()
            }
            faultInjector.inject(VoiceStoreBoundary.PROFILE_RENAME)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Atomically persists the versioned metadata index. */
    fun writeIndex(index: VoiceProfileIndex) {
        ensureDirectory()
        val temp = File(storeDir, "$INDEX_FILE_NAME.$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                faultInjector.inject(VoiceStoreBoundary.INDEX_TEMP_WRITE)
                out.write(encodeIndex(index).toByteArray(Charsets.UTF_8))
                faultInjector.inject(VoiceStoreBoundary.INDEX_TEMP_FSYNC)
                out.fd.sync()
            }
            faultInjector.inject(VoiceStoreBoundary.INDEX_RENAME)
            Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Removes a profile document; absent files are not an error. */
    fun deleteProfileDocument(id: VoiceProfileId) {
        val target = profileFile(id)
        if (target.exists() && !target.delete()) {
            throw IOException("Could not delete voice profile document: ${target.path}")
        }
    }

    /** Reads the durable index, returning null when no committed index exists. */
    fun readIndex(): VoiceProfileIndex? {
        if (!indexFile.isFile) return null
        return try {
            decodeIndex(indexFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** Reads and decodes one profile document, or null when absent/undecodable. */
    fun readProfileDocument(id: VoiceProfileId): DecodedVoiceProfileDocument? {
        val file = profileFile(id)
        if (!file.isFile) return null
        return try {
            VoiceProfileCodec.decode(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** Total bytes occupied by committed profile documents (excludes index/temps). */
    fun aggregateDocumentBytes(): Long {
        if (!storeDir.isDirectory) return 0L
        val files = storeDir.listFiles() ?: return 0L
        var total = 0L
        for (file in files) {
            if (file.isFile && file.name.endsWith(".json") && file.name != INDEX_FILE_NAME) {
                total += file.length()
            }
        }
        return total
    }

    /**
     * Startup reconciliation. Sweeps orphaned temp files, then classifies every index
     * entry. Missing, corrupt, and incompatible documents keep their index metadata for
     * diagnostics but are never published as selectable; partial/unindexed documents are
     * removed so they cannot surface later.
     */
    fun reconcile(currentModel: VoiceProfileModelMetadata): List<VoiceProfileRecordState> {
        ensureDirectory()
        sweepOrphans()
        val index = readIndex() ?: return emptyList()
        return index.entries.map { entry -> reconcileEntry(entry, currentModel) }
    }

    private fun reconcileEntry(
        entry: VoiceProfileIndexEntry,
        currentModel: VoiceProfileModelMetadata,
    ): VoiceProfileRecordState {
        val file = profileFile(entry.id)
        if (!file.isFile) {
            return VoiceProfileRecordState.Unavailable(
                entry,
                VoiceProfileUnavailableReason.MISSING_FILE,
                "Profile document ${entry.fileName} is missing",
            )
        }
        val decoded =
            try {
                VoiceProfileCodec.decode(file.readText(Charsets.UTF_8))
            } catch (e: SupertonicStyleException) {
                return VoiceProfileRecordState.Unavailable(
                    entry,
                    VoiceProfileUnavailableReason.CORRUPT_DOCUMENT,
                    "Profile document ${entry.fileName} is corrupt: ${e.message}",
                )
            } catch (e: Exception) {
                return VoiceProfileRecordState.Unavailable(
                    entry,
                    VoiceProfileUnavailableReason.CORRUPT_DOCUMENT,
                    "Profile document ${entry.fileName} could not be read: ${e.message}",
                )
            }
        val model = decoded.model
        if (model == null) {
            return VoiceProfileRecordState.Unverified(entry)
        }
        if (model != currentModel) {
            return VoiceProfileRecordState.Unavailable(
                entry,
                VoiceProfileUnavailableReason.INCOMPATIBLE_MODEL,
                "Profile ${entry.fileName} targets ${model.family}/${model.version}, " +
                    "current model is ${currentModel.family}/${currentModel.version}",
            )
        }
        return VoiceProfileRecordState.Available(entry)
    }

    /**
     * Removes temp files and any `.json` document not referenced by the committed index,
     * so an interrupted commit never leaves a publishable orphan.
     */
    private fun sweepOrphans() {
        val files = storeDir.listFiles() ?: return
        val indexedNames = readIndex()?.entries?.map { it.fileName }?.toSet() ?: emptySet()
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(".$TEMP_SUFFIX")) {
                file.delete()
            } else if (file.name.endsWith(".json") && file.name != INDEX_FILE_NAME && file.name !in indexedNames) {
                file.delete()
            }
        }
    }

    private fun encodeIndex(index: VoiceProfileIndex): String {
        val root = JSONObject()
        root.put("version", index.version)
        val entries = JSONArray()
        for (entry in index.entries) {
            val obj = JSONObject()
            obj.put("id", entry.id.value)
            obj.put("display_name", entry.displayName)
            obj.put("kind", entry.kind.name)
            obj.put("model_family", entry.modelFamily)
            obj.put("model_version", entry.modelVersion)
            entries.put(obj)
        }
        root.put("entries", entries)
        return root.toString()
    }

    private fun decodeIndex(json: String): VoiceProfileIndex {
        val root = JSONObject(json)
        val version = root.getInt("version")
        val entriesArray = root.getJSONArray("entries")
        val entries = mutableListOf<VoiceProfileIndexEntry>()
        for (i in 0 until entriesArray.length()) {
            val obj = entriesArray.getJSONObject(i)
            entries.add(
                VoiceProfileIndexEntry(
                    id = VoiceProfileId(obj.getString("id")),
                    displayName = obj.getString("display_name"),
                    kind = VoiceProfileKind.valueOf(obj.getString("kind")),
                    modelFamily = obj.optString("model_family", ""),
                    modelVersion = obj.optString("model_version", ""),
                )
            )
        }
        return VoiceProfileIndex(version, entries)
    }

    companion object {
        const val INDEX_FILE_NAME: String = "index.json"
        private const val TEMP_SUFFIX: String = "tmp"
    }
}
