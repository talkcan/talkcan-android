package io.talkcan.mount.saf

import io.talkcan.resource.MountBindingLimits
import io.talkcan.resource.PlatformGrantBlob
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The read/write grant bits actually persisted by the platform for a tree. */
data class SafGrantedFlags(
    val read: Boolean,
    val write: Boolean,
)

/**
 * Decoded opaque SAF grant payload.
 *
 * This type never crosses the adapter boundary into bindings, Lua, logs, or
 * provider objects; only [SafGrantCodec.encode] bytes do.
 */
data class SafGrantPayload(
    val treeUri: String,
    val granted: SafGrantedFlags,
)

/**
 * Canonical opaque encoding of a persisted SAF tree grant.
 *
 * Layout: `[version:1][flagBits:4 big-endian][treeUri UTF-8]` where bit 0 is
 * the read grant and bit 1 is the write grant. The encoding is deterministic
 * so byte equality of two blobs is a sound identity test for "same platform
 * grant" reference counting in the binding store.
 */
object SafGrantCodec {
    const val VERSION: Int = 1

    private const val HEADER_BYTES: Int = 5
    private const val FLAG_READ: Int = 0x1
    private const val FLAG_WRITE: Int = 0x2

    /**
     * Hard bound on the tree URI's UTF-8 bytes, chosen so the complete blob
     * (header + URI) never exceeds [MountBindingLimits.MAX_GRANT_BLOB_BYTES].
     */
    const val MAX_URI_BYTES: Int = MountBindingLimits.MAX_GRANT_BLOB_BYTES - HEADER_BYTES

    fun encode(payload: SafGrantPayload): PlatformGrantBlob {
        val uriBytes = payload.treeUri.toByteArray(StandardCharsets.UTF_8)
        require(uriBytes.isNotEmpty()) { "SAF tree URI must not be empty" }
        require(uriBytes.size <= MAX_URI_BYTES) {
            "SAF tree URI exceeds $MAX_URI_BYTES UTF-8 bytes"
        }
        var bits = 0
        if (payload.granted.read) bits = bits or FLAG_READ
        if (payload.granted.write) bits = bits or FLAG_WRITE
        val out = ByteBuffer.allocate(HEADER_BYTES + uriBytes.size)
        out.put(VERSION.toByte())
        out.putInt(bits)
        out.put(uriBytes)
        return PlatformGrantBlob(out.array())
    }

    /** Returns null for any malformed, truncated, oversized, or unknown-version blob. */
    fun decode(blob: PlatformGrantBlob): SafGrantPayload? {
        val bytes = blob.toByteArray()
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0].toInt() != VERSION) return null
        val bits = ByteBuffer.wrap(bytes, 1, 4).int
        if (bits and (FLAG_READ or FLAG_WRITE).inv() != 0) return null
        val uriBytes = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        if (uriBytes.isEmpty() || uriBytes.size > MAX_URI_BYTES) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val uri = try {
            decoder.decode(ByteBuffer.wrap(uriBytes)).toString()
        } catch (_: Exception) {
            return null
        }
        return SafGrantPayload(
            treeUri = uri,
            granted = SafGrantedFlags(
                read = bits and FLAG_READ != 0,
                write = bits and FLAG_WRITE != 0,
            ),
        )
    }
}
