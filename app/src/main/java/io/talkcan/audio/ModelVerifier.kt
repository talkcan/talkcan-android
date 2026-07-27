package io.talkcan.audio

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Loads the bundled `model-hashes.json` manifest and verifies that the model
 * files present on disk match it. Used by the initial setup screen at launch
 * to decide whether the model-download step must (re)run.
 *
 * The manifest maps each model set's directory name to its version, HF repo,
 * and per-file SHA-256 hashes. Manifest `files` keys are HuggingFace paths
 * (flat for parakeet, `onnx/`- or `voice_styles/`-prefixed for supertonic);
 * the on-disk filename is the path's basename, matching the flat layout consumed
 * by the Kotlin ONNX model loaders.
 */
data class ModelHashManifest(
    val sets: Map<String, ModelSetHash>,
) {
    fun set(dirName: String): ModelSetHash =
        sets[dirName] ?: error("Model set '$dirName' not present in manifest")
}

data class ModelSetHash(
    val dirName: String,
    val version: String,
    val repo: String,
    val files: List<FileHash>,
)

data class FileHash(
    val path: String,
    val sha256: String,
)

sealed interface ModelSetStatus {
    /** Version marker matches the manifest and every file's SHA-256 verifies. */
    object Valid : ModelSetStatus

    /** Missing, version-mismatched, or hash-corrupted — must (re)download. */
    object NeedsDownload : ModelSetStatus
}

object ModelVerifier {
    const val MARKER_NAME = ".talkcan_assets_version"
    const val PARAKEET_DIR = "parakeet-tdt-0.6b-v3-int8"
    const val SUPERTONIC_DIR = "supertonic-3"

    /** Load and parse `model-hashes.json` from APK assets. */
    fun loadManifest(context: Context): ModelHashManifest {
        val json = context.assets.open("model-hashes.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val sets = LinkedHashMap<String, ModelSetHash>()
        for (dirName in root.keys()) {
            val setObj = root.getJSONObject(dirName)
            val version = setObj.getString("version")
            val repo = setObj.getString("repo")
            val filesObj = setObj.getJSONObject("files")
            val files = ArrayList<FileHash>()
            for (path in filesObj.keys()) {
                val raw = filesObj.getString(path)
                val sha = raw.removePrefix("sha256:")
                files.add(FileHash(path, sha))
            }
            sets[dirName] = ModelSetHash(dirName, version, repo, files)
        }
        return ModelHashManifest(sets)
    }

    /** True iff every model set in the manifest is present and valid on disk. */
    fun isComplete(context: Context, manifest: ModelHashManifest = loadManifest(context)): Boolean =
        manifest.sets.keys.all { status(context, manifest, it) == ModelSetStatus.Valid }

    /**
     * Full integrity check for one model set: requires the version marker to
     * match the manifest AND every file's SHA-256 to verify. This is the
     * launch-time check that triggers the setup screen's re-download path.
     */
    fun status(
        context: Context,
        manifest: ModelHashManifest,
        dirName: String,
    ): ModelSetStatus {
        val set = manifest.set(dirName)
        val dir = File(context.filesDir, dirName)
        val marker = File(dir, MARKER_NAME)
        if (!dir.exists() || !marker.exists()) return ModelSetStatus.NeedsDownload
        if (marker.readText().trim() != set.version) return ModelSetStatus.NeedsDownload
        for (file in set.files) {
            val local = File(dir, file.path.substringAfterLast('/'))
            if (!local.exists() || local.length() == 0L) return ModelSetStatus.NeedsDownload
            if (sha256OfFile(local) != file.sha256) return ModelSetStatus.NeedsDownload
        }
        return ModelSetStatus.Valid
    }

    private fun sha256OfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
