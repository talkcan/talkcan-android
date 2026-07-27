package io.talkcan.mount.saf

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.model.ChannelImplementationId
import io.talkcan.resource.MountBindingState
import io.talkcan.resource.MountBindingStatus
import io.talkcan.resource.MountBindingStore
import io.talkcan.resource.PlatformGrantBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Focused JVM tests for the SAF adapter against the real transactional
 * [MountBindingStore] (temp file) and a fake [SafGrantController].
 */
class SafMountAdapterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val uriA = "content://com.android.externalstorage.documents/tree/primary%3AJournal"
    private val uriB = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    private val declaration = PackageMountDeclaration(
        id = "output",
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        required = true,
        label = "Journal directory",
        help = null,
    )
    private val implementationId = ChannelImplementationId("builtin:debug")
    private val instance1 = "instance-1"
    private val instance2 = "instance-2"

    private val grants = FakeSafGrantController()
    private lateinit var store: MountBindingStore
    private lateinit var adapter: SafMountAdapter

    private fun newAdapter(storeFile: File = tempFolder.newFile("mount-bindings.json")): SafMountAdapter {
        store = MountBindingStore(storeFile)
        assertEquals(true, (store.load() as? io.talkcan.resource.MountBindingLoadResult.Loaded) != null)
        return SafMountAdapter(store, grants)
    }

    private fun bind(instance: String, uri: String): SafSelectionOutcome {
        return adapter.completeSelection(
            channelInstanceId = instance,
            implementationId = implementationId,
            declaration = declaration,
            outcome = SafTreePickerOutcome.Selected(uri),
        )
    }

    @Test
    fun `happy path commits an active binding with only the opaque grant blob`() {
        adapter = newAdapter()

        val outcome = bind(instance1, uriA)

        assertTrue(outcome is SafSelectionOutcome.Bound)
        val binding = store.currentBinding(instance1, implementationId, declaration.id)!!
        assertEquals(MountBindingStatus.AVAILABLE, binding.status)
        assertEquals(MountBindingState.ACTIVE, binding.state)
        assertEquals(
            SafGrantPayload(uriA, SafGrantedFlags(read = true, write = true)),
            SafGrantCodec.decode(binding.grant),
        )
        assertEquals(listOf(uriA to SafRequestedAccess(read = true, write = true)), grants.takeCalls)
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun `picker cancellation retains the last valid binding untouched`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.takeCalls.clear()

        val outcome = adapter.completeSelection(
            instance1,
            implementationId,
            declaration,
            SafTreePickerOutcome.Cancelled,
        )

        assertEquals(SafSelectionOutcome.RetainedPrior, outcome)
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
        assertTrue(grants.takeCalls.isEmpty())
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun `non-content picker result fails without taking a grant`() {
        adapter = newAdapter()

        val outcome = adapter.completeSelection(
            instance1,
            implementationId,
            declaration,
            SafTreePickerOutcome.Selected("file:///storage/emulated/0/Journal"),
        )

        assertEquals(SafSelectionOutcome.Failed(SafSelectionFailure.PICKER_RESULT_NOT_TREE), outcome)
        assertTrue(grants.takeCalls.isEmpty())
        assertNull(store.currentBinding(instance1, implementationId, declaration.id))
    }

    @Test
    fun `persistable grant refusal retains the prior binding and releases nothing`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.takeCalls.clear()
        grants.takeResult = SafTakeResult.Failed(SafTakeFailure.REJECTED_BY_PROVIDER)

        val outcome = bind(instance1, uriB)

        assertEquals(SafSelectionOutcome.Failed(SafSelectionFailure.GRANT_NOT_PERSISTED), outcome)
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun `unreachable tree rolls back the fresh grant and preserves the prior binding`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.releaseCalls.clear()
        grants.probe = SafTreeProbe.Unreachable

        val outcome = bind(instance1, uriB)

        assertEquals(SafSelectionOutcome.Failed(SafSelectionFailure.TREE_UNREACHABLE), outcome)
        assertEquals(listOf(uriB), grants.releaseCalls)
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
        assertFalse(grants.persistedGrants().any { it.treeUri == uriB })
    }

    @Test
    fun `read-only provider grant fails read-write selection and releases the fresh grant`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.releaseCalls.clear()
        grants.takeResult = SafTakeResult.Taken(SafGrantedFlags(read = true, write = false))

        val outcome = bind(instance1, uriB)

        assertEquals(SafSelectionOutcome.Failed(SafSelectionFailure.REQUESTED_ACCESS_NOT_GRANTED), outcome)
        assertEquals(listOf(uriB), grants.releaseCalls)
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
    }

    @Test
    fun `provider without directory create support fails read-write selection`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.releaseCalls.clear()
        grants.probe = SafTreeProbe.Reachable(directoryCreateSupported = false)

        val outcome = bind(instance1, uriB)

        assertEquals(SafSelectionOutcome.Failed(SafSelectionFailure.REQUESTED_ACCESS_NOT_GRANTED), outcome)
        assertEquals(listOf(uriB), grants.releaseCalls)
    }

    @Test
    fun `store rejection rolls back the fresh grant and preserves the prior binding`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        // A second, never-loaded store fails every mutation with NotLoaded.
        val unloaded = MountBindingStore(tempFolder.newFile("unloaded.json"))
        val failing = SafMountAdapter(unloaded, grants)
        grants.releaseCalls.clear()

        val outcome = failing.completeSelection(
            instance1,
            implementationId,
            declaration,
            SafTreePickerOutcome.Selected(uriB),
        )

        assertEquals(SafSelectionOutcome.Failed(SafSelectionFailure.STORE_REJECTED), outcome)
        assertEquals(listOf(uriB), grants.releaseCalls)
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
    }

    @Test
    fun `replacement releases the orphaned prior grant`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.releaseCalls.clear()

        val outcome = bind(instance1, uriB)

        assertTrue(outcome is SafSelectionOutcome.Bound)
        assertEquals(listOf(uriA), grants.releaseCalls)
        assertEquals(uriB, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
        assertFalse(grants.persistedGrants().any { it.treeUri == uriA })
    }

    @Test
    fun `replacement never releases a grant still referenced by another binding`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        bind(instance2, uriA)
        grants.releaseCalls.clear()

        val outcome = bind(instance1, uriB)

        assertTrue(outcome is SafSelectionOutcome.Bound)
        assertFalse("shared grant must not be released", grants.releaseCalls.contains(uriA))
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance2, implementationId, declaration.id)!!.grant)!!.treeUri)
        assertTrue(grants.persistedGrants().any { it.treeUri == uriA })
    }

    @Test
    fun `reselecting the same tree keeps the single grant alive`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.releaseCalls.clear()

        val outcome = bind(instance1, uriA)

        assertTrue(outcome is SafSelectionOutcome.Bound)
        assertTrue(grants.releaseCalls.isEmpty())
        assertTrue(grants.persistedGrants().any { it.treeUri == uriA })
    }

    @Test
    fun `status mapping covers revoked, read-only, unreachable, corrupt, and missing bindings`() {
        adapter = newAdapter()

        // No binding at all.
        assertEquals(MountBindingStatus.UNAVAILABLE, adapter.currentStatus(instance1, implementationId, declaration))

        bind(instance1, uriA)
        assertEquals(MountBindingStatus.AVAILABLE, adapter.currentStatus(instance1, implementationId, declaration))

        // Grant revoked by the platform.
        grants.persisted.clear()
        assertEquals(MountBindingStatus.NEEDS_REAUTHORIZATION, adapter.currentStatus(instance1, implementationId, declaration))

        // Grant restored but downgraded to read-only.
        grants.persisted += SafPersistedGrant(uriA, readPermission = true, writePermission = false)
        assertEquals(MountBindingStatus.READ_ONLY, adapter.currentStatus(instance1, implementationId, declaration))

        // Provider disappears entirely.
        grants.persisted.clear()
        grants.persisted += SafPersistedGrant(uriA, readPermission = true, writePermission = true)
        grants.probe = SafTreeProbe.Unreachable
        assertEquals(MountBindingStatus.UNAVAILABLE, adapter.currentStatus(instance1, implementationId, declaration))

        // Corrupt stored blob is unavailable, never an Android detail leak.
        grants.probe = SafTreeProbe.Reachable(directoryCreateSupported = true)
        store.replaceBinding(
            declaration,
            instance1,
            implementationId,
            PlatformGrantBlob(byteArrayOf(9, 0, 0, 0, 3, 120)),
            MountBindingStatus.AVAILABLE,
        )
        assertEquals(MountBindingStatus.UNAVAILABLE, adapter.currentStatus(instance1, implementationId, declaration))
    }

    @Test
    fun `syncStatus persists the mapped portable status onto the binding`() {
        adapter = newAdapter()
        bind(instance1, uriA)

        assertEquals(MountBindingStatus.AVAILABLE, adapter.syncStatus(instance1, implementationId, declaration))

        grants.persisted.clear()
        assertEquals(MountBindingStatus.NEEDS_REAUTHORIZATION, adapter.syncStatus(instance1, implementationId, declaration))
        assertEquals(
            MountBindingStatus.NEEDS_REAUTHORIZATION,
            store.currentBinding(instance1, implementationId, declaration.id)!!.status,
        )
    }

    @Test
    fun `release removes the binding and releases the unreferenced grant`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.releaseCalls.clear()

        val outcome = adapter.release(instance1, implementationId, declaration.id)

        assertEquals(SafReleaseOutcome.Released, outcome)
        assertEquals(listOf(uriA), grants.releaseCalls)
        assertNull(store.currentBinding(instance1, implementationId, declaration.id))
    }

    @Test
    fun `release of a shared grant removes only the binding`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        bind(instance2, uriA)
        grants.releaseCalls.clear()

        val outcome = adapter.release(instance1, implementationId, declaration.id)

        assertEquals(SafReleaseOutcome.RemovedGrantStillReferenced, outcome)
        assertTrue(grants.releaseCalls.isEmpty())
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance2, implementationId, declaration.id)!!.grant)!!.treeUri)
    }

    @Test
    fun `release reports when the platform no longer holds the grant`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        grants.persisted.clear()

        val outcome = adapter.release(instance1, implementationId, declaration.id)

        assertEquals(SafReleaseOutcome.GrantAlreadyGone, outcome)
        assertNull(store.currentBinding(instance1, implementationId, declaration.id))
    }

    @Test
    fun `release without a binding is a no-op`() {
        adapter = newAdapter()
        assertEquals(SafReleaseOutcome.NoBinding, adapter.release(instance1, implementationId, declaration.id))
    }

    @Test
    fun `release against a failed store leaves bindings untouched`() {
        adapter = newAdapter()
        bind(instance1, uriA)
        val unloaded = MountBindingStore(tempFolder.newFile("unloaded-release.json"))
        val failing = SafMountAdapter(unloaded, grants)

        assertEquals(SafReleaseOutcome.StoreFailed, failing.release(instance1, implementationId, declaration.id))
        assertEquals(uriA, SafGrantCodec.decode(store.currentBinding(instance1, implementationId, declaration.id)!!.grant)!!.treeUri)
    }

    @Test
    fun `releaseInstance releases only that instance's unreferenced grants`() {
        adapter = newAdapter()
        val cacheDeclaration = PackageMountDeclaration(
            id = "cache",
            kind = PackageMountKind.DIRECTORY_TREE,
            access = PackageMountAccess.READ_WRITE,
            required = true,
            label = "Cache directory",
            help = null,
        )
        bind(instance1, uriA)
        adapter.completeSelection(instance1, implementationId, cacheDeclaration, SafTreePickerOutcome.Selected(uriB))
        // Another instance shares uriA.
        bind(instance2, uriA)
        grants.releaseCalls.clear()

        val outcome = adapter.releaseInstance(instance1)

        assertEquals(SafReleaseOutcome.Released, outcome)
        assertTrue(grants.releaseCalls.contains(uriB))
        assertFalse("grant shared with instance-2 must not be released", grants.releaseCalls.contains(uriA))
        assertTrue(store.bindingsForInstance(instance1).isEmpty())
        assertEquals(1, store.bindingsForInstance(instance2).size)
    }
}

private class FakeSafGrantController : SafGrantController {
    val takeCalls = mutableListOf<Pair<String, SafRequestedAccess>>()
    val releaseCalls = mutableListOf<String>()
    val persisted = mutableListOf<SafPersistedGrant>()
    var probe: SafTreeProbe = SafTreeProbe.Reachable(directoryCreateSupported = true)
    var takeResult: SafTakeResult = SafTakeResult.Taken(SafGrantedFlags(read = true, write = true))

    override fun takePersistable(treeUri: String, requested: SafRequestedAccess): SafTakeResult {
        takeCalls += treeUri to requested
        val result = takeResult
        if (result is SafTakeResult.Taken) {
            persisted.removeAll { it.treeUri == treeUri }
            persisted += SafPersistedGrant(treeUri, result.granted.read, result.granted.write)
        }
        return result
    }

    override fun releasePersistable(treeUri: String): Boolean {
        releaseCalls += treeUri
        return persisted.removeAll { it.treeUri == treeUri }
    }

    override fun persistedGrants(): List<SafPersistedGrant> = persisted.toList()

    override fun probeTree(treeUri: String): SafTreeProbe = probe
}
