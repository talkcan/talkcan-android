package io.talkcan.mount.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** One SAF tree permission currently persisted by the platform. */
data class SafPersistedGrant(
    val treeUri: String,
    val readPermission: Boolean,
    val writePermission: Boolean,
)

/** Live `DocumentsContract` probe of a stored tree URI. */
sealed interface SafTreeProbe {
    /** The provider answered and the tree root document exists. */
    data class Reachable(val directoryCreateSupported: Boolean) : SafTreeProbe

    /** The provider disappeared, the root was moved/deleted, or the query failed. */
    data object Unreachable : SafTreeProbe
}

/** Result of taking a persistable URI permission. */
sealed interface SafTakeResult {
    /** The platform persisted the grant; [granted] is what was actually granted. */
    data class Taken(val granted: SafGrantedFlags) : SafTakeResult

    /** The platform refused to persist the grant. */
    data class Failed(val reason: SafTakeFailure) : SafTakeResult
}

/** Portable reasons a persistable grant could not be taken. */
enum class SafTakeFailure {
    /** The returned URI is not a usable content tree. */
    UNSUPPORTED_URI,

    /** The provider refused the grant or did not record it as persistable. */
    REJECTED_BY_PROVIDER,
}

/**
 * Platform boundary for SAF grants.
 *
 * This port is the only place the adapter touches `ContentResolver` /
 * `DocumentsContract`; the orchestration logic and tests stay platform-free.
 */
interface SafGrantController {
    /**
     * Takes a persistable permission for exactly the requested access.
     * The returned granted flags come from the platform's persisted table,
     * never from the request.
     */
    fun takePersistable(treeUri: String, requested: SafRequestedAccess): SafTakeResult

    /** Releases any persisted permission for the tree; false if none was held. */
    fun releasePersistable(treeUri: String): Boolean

    /** Snapshot of every SAF permission the platform currently persists for us. */
    fun persistedGrants(): List<SafPersistedGrant>

    /** Bounded reachability/write-support probe of the tree root document. */
    fun probeTree(treeUri: String): SafTreeProbe
}

/**
 * Production SAF grant controller over the app's [ContentResolver].
 *
 * Never resolves trees to filesystem paths and never depends on the legacy
 * all-files storage permission: authority stays in the persisted `content://`
 * grant and is exercised only through document-provider operations.
 */
class AndroidSafGrantController(
    private val contentResolver: ContentResolver,
) : SafGrantController {

    override fun takePersistable(treeUri: String, requested: SafRequestedAccess): SafTakeResult {
        val uri = parseTreeUri(treeUri) ?: return SafTakeResult.Failed(SafTakeFailure.UNSUPPORTED_URI)
        val modeFlags = requested.intentFlags() and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val took = try {
            contentResolver.takePersistableUriPermission(uri, modeFlags)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!took) return SafTakeResult.Failed(SafTakeFailure.REJECTED_BY_PROVIDER)
        // The authoritative granted flags come from the persisted table, not the request.
        val entry = try {
            contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        } catch (_: RuntimeException) {
            null
        } ?: return SafTakeResult.Failed(SafTakeFailure.REJECTED_BY_PROVIDER)
        return SafTakeResult.Taken(
            SafGrantedFlags(read = entry.isReadPermission, write = entry.isWritePermission),
        )
    }

    override fun releasePersistable(treeUri: String): Boolean {
        val uri = parseTreeUri(treeUri) ?: return false
        return try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    override fun persistedGrants(): List<SafPersistedGrant> =
        try {
            contentResolver.persistedUriPermissions.map { permission ->
                SafPersistedGrant(
                    treeUri = permission.uri.toString(),
                    readPermission = permission.isReadPermission,
                    writePermission = permission.isWritePermission,
                )
            }
        } catch (_: RuntimeException) {
            emptyList()
        }

    override fun probeTree(treeUri: String): SafTreeProbe {
        val uri = parseTreeUri(treeUri) ?: return SafTreeProbe.Unreachable
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val rootDocument = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
            contentResolver.query(
                rootDocument,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return SafTreeProbe.Unreachable
                }
                val flags = cursor.getInt(0)
                SafTreeProbe.Reachable(
                    directoryCreateSupported =
                        flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0,
                )
            } ?: SafTreeProbe.Unreachable
        } catch (_: IllegalArgumentException) {
            SafTreeProbe.Unreachable
        } catch (_: SecurityException) {
            SafTreeProbe.Unreachable
        } catch (_: UnsupportedOperationException) {
            SafTreeProbe.Unreachable
        } catch (_: NullPointerException) {
            // A vanished provider can surface as NPE inside the resolver stack.
            SafTreeProbe.Unreachable
        }
    }

    private fun parseTreeUri(treeUri: String): Uri? {
        val uri = try {
            Uri.parse(treeUri)
        } catch (_: RuntimeException) {
            return null
        }
        return if (uri.scheme == ContentResolver.SCHEME_CONTENT) uri else null
    }
}
