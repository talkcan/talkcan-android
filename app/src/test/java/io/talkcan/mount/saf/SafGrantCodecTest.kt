package io.talkcan.mount.saf

import io.talkcan.resource.PlatformGrantBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SafGrantCodecTest {

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3AJournal"

    @Test
    fun `encode produces the canonical version-flags-uri layout`() {
        val blob = SafGrantCodec.encode(
            SafGrantPayload(uri, SafGrantedFlags(read = true, write = true)),
        )

        val expected = mutableListOf<Byte>(1, 0, 0, 0, 3)
        expected += uri.toByteArray(StandardCharsets.UTF_8).toList()
        assertArrayEquals(expected.toByteArray(), blob.toByteArray())
    }

    @Test
    fun `encode is deterministic so blob byte equality is grant identity`() {
        val a = SafGrantCodec.encode(SafGrantPayload(uri, SafGrantedFlags(true, true)))
        val b = SafGrantCodec.encode(SafGrantPayload(uri, SafGrantedFlags(true, true)))
        val c = SafGrantCodec.encode(SafGrantPayload(uri, SafGrantedFlags(true, false)))

        assertEquals(a, b)
        assertArrayEquals(a.toByteArray(), b.toByteArray())
        assertTrue(a != c)
    }

    @Test
    fun `round trip preserves uri and granted flags`() {
        val payload = SafGrantPayload(uri, SafGrantedFlags(read = true, write = false))
        assertEquals(payload, SafGrantCodec.decode(SafGrantCodec.encode(payload)))
    }

    @Test
    fun `decode rejects wrong version, truncation, unknown bits, invalid utf-8, and empty uri`() {
        assertNull(SafGrantCodec.decode(PlatformGrantBlob(byteArrayOf(2, 0, 0, 0, 3, 99))))
        assertNull(SafGrantCodec.decode(PlatformGrantBlob(byteArrayOf(1, 0, 0))))
        assertNull(SafGrantCodec.decode(PlatformGrantBlob(byteArrayOf(1, 0, 0, 0, 4, 99))))
        assertNull(SafGrantCodec.decode(PlatformGrantBlob(byteArrayOf(1, 0, 0, 0, 1, 0xFF.toByte()))))
        assertNull(SafGrantCodec.decode(PlatformGrantBlob(byteArrayOf(1, 0, 0, 0, 3))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects an empty uri`() {
        SafGrantCodec.encode(SafGrantPayload("", SafGrantedFlags(true, true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects a uri beyond the blob bound`() {
        val oversized = "content://t/" + "a".repeat(SafGrantCodec.MAX_URI_BYTES)
        SafGrantCodec.encode(SafGrantPayload(oversized, SafGrantedFlags(true, true)))
    }

    @Test
    fun `a uri at exactly the bound encodes within the store blob limit`() {
        val maxed = "content://t/" + "a".repeat(SafGrantCodec.MAX_URI_BYTES - "content://t/".length)
        val blob = SafGrantCodec.encode(SafGrantPayload(maxed, SafGrantedFlags(true, true)))
        assertEquals(maxed, SafGrantCodec.decode(blob)?.treeUri)
    }
}
