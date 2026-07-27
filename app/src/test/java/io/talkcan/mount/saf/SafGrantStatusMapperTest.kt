package io.talkcan.mount.saf

import io.talkcan.resource.MountBindingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SafGrantStatusMapperTest {

    private val readWrite = SafRequestedAccess(read = true, write = true)
    private val uri = "content://provider/tree/x"

    private fun persisted(read: Boolean = true, write: Boolean = true) =
        SafPersistedGrant(treeUri = uri, readPermission = read, writePermission = write)

    private val writableProbe = SafTreeProbe.Reachable(directoryCreateSupported = true)

    @Test
    fun `vanished platform grant needs reauthorization`() {
        assertEquals(
            MountBindingStatus.NEEDS_REAUTHORIZATION,
            SafGrantStatusMapper.map(readWrite, null, writableProbe),
        )
    }

    @Test
    fun `unknown probe with a persisted grant is unavailable`() {
        assertEquals(
            MountBindingStatus.UNAVAILABLE,
            SafGrantStatusMapper.map(readWrite, persisted(), null),
        )
    }

    @Test
    fun `unreachable provider is unavailable`() {
        assertEquals(
            MountBindingStatus.UNAVAILABLE,
            SafGrantStatusMapper.map(readWrite, persisted(), SafTreeProbe.Unreachable),
        )
    }

    @Test
    fun `lost read grant needs reauthorization`() {
        assertEquals(
            MountBindingStatus.NEEDS_REAUTHORIZATION,
            SafGrantStatusMapper.map(readWrite, persisted(read = false), writableProbe),
        )
    }

    @Test
    fun `lost write grant is read-only`() {
        assertEquals(
            MountBindingStatus.READ_ONLY,
            SafGrantStatusMapper.map(readWrite, persisted(write = false), writableProbe),
        )
    }

    @Test
    fun `provider without directory create support is read-only`() {
        assertEquals(
            MountBindingStatus.READ_ONLY,
            SafGrantStatusMapper.map(
                readWrite,
                persisted(),
                SafTreeProbe.Reachable(directoryCreateSupported = false),
            ),
        )
    }

    @Test
    fun `satisfied read-write grant is available`() {
        assertEquals(
            MountBindingStatus.AVAILABLE,
            SafGrantStatusMapper.map(readWrite, persisted(), writableProbe),
        )
    }

    @Test
    fun `read-only request ignores missing write support`() {
        assertEquals(
            MountBindingStatus.AVAILABLE,
            SafGrantStatusMapper.map(
                SafRequestedAccess(read = true, write = false),
                persisted(write = false),
                SafTreeProbe.Reachable(directoryCreateSupported = false),
            ),
        )
    }
}
