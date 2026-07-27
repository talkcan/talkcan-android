package io.talkcan.mount.saf.vfs

import io.talkcan.model.ChannelImplementationId
import io.talkcan.mount.saf.SafGrantCodec
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.mount.saf.SafGrantedFlags
import io.talkcan.mount.saf.SafGrantPayload
import io.talkcan.mount.saf.SafPersistedGrant
import io.talkcan.mount.saf.SafRequestedAccess
import io.talkcan.mount.saf.SafTakeResult
import io.talkcan.mount.saf.SafTreeProbe
import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.resource.MountBindingStatus
import io.talkcan.resource.MountBindingStore
import io.talkcan.storage.FilesystemErrorCode
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.MountAccessMode
import io.talkcan.storage.MountGrantFingerprint
import io.talkcan.storage.MountLeaseFacts
import io.talkcan.storage.MountResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Focused JVM tests for the SAF composition adapters ([SafVfsMountFactory],
 * [SafVfsMountResolver], [SafMountLeaseRevalidator]): live persisted-grant
 * validation before any backend is constructed, grant-fingerprint staleness,
 * and portable status normalization.
 */
class SafVfsCompositionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AJournal"
    private val implementationId = ChannelImplementationId("builtin:debug")
    private val instance = "instance-1"
    private val declaration = PackageMountDeclaration(
        id = "output",
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        required = true,
        label = "Journal directory",
        help = null,
    )

    private fun blob(read: Boolean = true, write: Boolean = true) =
        SafGrantCodec.encode(SafGrantPayload(treeUri, SafGrantedFlags(read = read, write = write)))

    private fun factory(grants: FakeGrants, gateway: FakeSafDocumentGateway) =
        SafVfsMountFactory(grants) { _ -> gateway }

    private fun newStore(): MountBindingStore {
        val store = MountBindingStore(tempFolder.newFile("mount-bindings.json"))
        store.load()
        return store
    }

    private fun seedBinding(store: MountBindingStore, grant: io.talkcan.resource.PlatformGrantBlob) {
        store.replaceBinding(
            declaration = declaration,
            channelInstanceId = instance,
            implementationId = implementationId,
            grant = grant,
            status = MountBindingStatus.AVAILABLE,
        )
    }

    // -- factory -------------------------------------------------------------

    @Test
    fun factoryRejectsAnUndecodableGrantWithoutBuildingABackend() {
        val grants = FakeGrants()
        val backend = factory(grants, FakeSafDocumentGateway())
        val result = backend.resolve(
            io.talkcan.resource.PlatformGrantBlob(byteArrayOf(9, 9, 9)),
            "output",
            1L,
            MountAccessMode.READ_WRITE,
        )
        assertEquals(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun factoryRequiresTheGrantToStillBePersisted() {
        val grants = FakeGrants() // no persisted grants: simulates revocation
        val backend = factory(grants, FakeSafDocumentGateway())
        val result = backend.resolve(blob(), "output", 1L, MountAccessMode.READ_WRITE)
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun factoryRequiresTheRequestedReadPermissionBit() {
        val grants = FakeGrants()
        grants.persisted += SafPersistedGrant(treeUri, readPermission = false, writePermission = true)
        val backend = factory(grants, FakeSafDocumentGateway())
        val result = backend.resolve(blob(), "output", 1L, MountAccessMode.READ_WRITE)
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun factoryReportsUnavailableWhenTheTreeRootIsUnreachable() {
        val grants = FakeGrants().withGrant()
        val gateway = FakeSafDocumentGateway()
        gateway.failQueryDocument = SafGatewayFailure.NOT_FOUND // root moved/deleted
        val backend = factory(grants, gateway)
        val result = backend.resolve(blob(), "output", 1L, MountAccessMode.READ_WRITE)
        assertEquals(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun factoryResolvesABackendConfinedToTheValidatedTreeRoot() {
        val grants = FakeGrants().withGrant()
        val gateway = FakeSafDocumentGateway()
        val backend = factory(grants, gateway)
        val resolved = (backend.resolve(blob(), "output", 7L, MountAccessMode.READ_WRITE) as FilesystemOutcome.Success).value

        assertEquals("output", resolved.declarationId)
        assertEquals(7L, resolved.generation)
        assertEquals(MountAccessMode.READ_WRITE, resolved.access)
        assertEquals(gateway.root.id, resolved.root.opaque)
        assertTrue(resolved.backend is SafDocumentTreeBackend)
        assertEquals(MountGrantFingerprint.of(blob().toByteArray()), resolved.grantFingerprint)
        assertEquals(resolved.grantFingerprint, resolved.mountToken)
    }

    @Test
    fun factoryFingerprintIsStableForTheSameGrantBytes() {
        val grants = FakeGrants().withGrant()
        val backend = factory(grants, FakeSafDocumentGateway())
        val first = (backend.resolve(blob(), "output", 1L, MountAccessMode.READ_WRITE) as FilesystemOutcome.Success).value
        val second = (backend.resolve(blob(), "output", 2L, MountAccessMode.READ_WRITE) as FilesystemOutcome.Success).value
        assertEquals(first.grantFingerprint, second.grantFingerprint)
    }

    // -- resolver ------------------------------------------------------------

    @Test
    fun resolverReportsUndeclaredForAMissingBinding() {
        val store = newStore()
        val resolver = SafVfsMountResolver(store, factory(FakeGrants().withGrant(), FakeSafDocumentGateway()), instance, implementationId) { 1L }
        val result = resolver.resolve("output")
        assertEquals(FilesystemErrorCode.E_CAPABILITY_UNDECLARED, (result as MountResolution.Failed).error.code)
    }

    @Test
    fun resolverResolvesAnActiveBindingThroughTheFactory() {
        val store = newStore()
        seedBinding(store, blob())
        val resolver = SafVfsMountResolver(store, factory(FakeGrants().withGrant(), FakeSafDocumentGateway()), instance, implementationId) { 3L }
        val resolved = (resolver.resolve("output") as MountResolution.Resolved).mount
        assertEquals(3L, resolved.generation)
        assertEquals("output", resolved.declarationId)
    }

    @Test
    fun resolverFailsClosedWhenTheLiveGrantWasRevoked() {
        val store = newStore()
        seedBinding(store, blob())
        val resolver = SafVfsMountResolver(store, factory(FakeGrants(), FakeSafDocumentGateway()), instance, implementationId) { 1L }
        val result = resolver.resolve("output")
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, (result as MountResolution.Failed).error.code)
    }

    // -- revalidator ---------------------------------------------------------

    private fun facts(fingerprint: String) =
        MountLeaseFacts(instance, "output", 1L, MountAccessMode.READ_WRITE, fingerprint)

    @Test
    fun revalidatorPassesForALiveAvailableGrant() {
        val store = newStore()
        seedBinding(store, blob())
        val grants = FakeGrants().withGrant()
        val revalidator = SafMountLeaseRevalidator(store, grants, implementationId)
        val result = revalidator.revalidate(facts(MountGrantFingerprint.of(blob().toByteArray())))
        assertTrue(result is FilesystemOutcome.Success)
    }

    @Test
    fun revalidatorReportsStaleWhenTheGrantFingerprintChanged() {
        val store = newStore()
        seedBinding(store, blob())
        val grants = FakeGrants().withGrant()
        val revalidator = SafMountLeaseRevalidator(store, grants, implementationId)
        val result = revalidator.revalidate(facts("a-different-fingerprint"))
        assertEquals(FilesystemErrorCode.E_STALE, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun revalidatorReportsReauthorizationWhenTheGrantVanished() {
        val store = newStore()
        seedBinding(store, blob())
        val grants = FakeGrants() // grant no longer persisted
        val revalidator = SafMountLeaseRevalidator(store, grants, implementationId)
        val result = revalidator.revalidate(facts(MountGrantFingerprint.of(blob().toByteArray())))
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun revalidatorReportsUnavailableWhenTheTreeIsUnreachable() {
        val store = newStore()
        seedBinding(store, blob())
        val grants = FakeGrants().withGrant()
        grants.probe = SafTreeProbe.Unreachable
        val revalidator = SafMountLeaseRevalidator(store, grants, implementationId)
        val result = revalidator.revalidate(facts(MountGrantFingerprint.of(blob().toByteArray())))
        assertEquals(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, (result as FilesystemOutcome.Failure).error.code)
    }

    @Test
    fun revalidatorKeepsReadsAuthorizedWhenOnlyWriteWasLost() {
        val store = newStore()
        seedBinding(store, blob())
        val grants = FakeGrants()
        grants.persisted += SafPersistedGrant(treeUri, readPermission = true, writePermission = false)
        grants.probe = SafTreeProbe.Reachable(directoryCreateSupported = false)
        val revalidator = SafMountLeaseRevalidator(store, grants, implementationId)
        val result = revalidator.revalidate(facts(MountGrantFingerprint.of(blob().toByteArray())))
        // READ_ONLY status passes revalidation; the write op itself fails closed at the gateway.
        assertTrue(result is FilesystemOutcome.Success)
    }

    @Test
    fun revalidatorReportsReauthorizationForAMissingBinding() {
        val store = newStore()
        val revalidator = SafMountLeaseRevalidator(store, FakeGrants().withGrant(), implementationId)
        val result = revalidator.revalidate(facts("any"))
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, (result as FilesystemOutcome.Failure).error.code)
    }

    private fun FakeGrants.withGrant(): FakeGrants = apply {
        persisted += SafPersistedGrant(treeUri, readPermission = true, writePermission = true)
    }

    /** Minimal controllable [SafGrantController] for composition tests. */
    private class FakeGrants : SafGrantController {
        val persisted = mutableListOf<SafPersistedGrant>()
        var probe: SafTreeProbe = SafTreeProbe.Reachable(directoryCreateSupported = true)

        override fun takePersistable(treeUri: String, requested: SafRequestedAccess): SafTakeResult =
            SafTakeResult.Taken(SafGrantedFlags(read = requested.read, write = requested.write))

        override fun releasePersistable(treeUri: String): Boolean = persisted.removeAll { it.treeUri == treeUri }

        override fun persistedGrants(): List<SafPersistedGrant> = persisted.toList()

        override fun probeTree(treeUri: String): SafTreeProbe = probe
    }
}
