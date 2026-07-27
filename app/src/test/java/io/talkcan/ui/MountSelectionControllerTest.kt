package io.talkcan.ui

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.model.ChannelImplementationId
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.mount.saf.SafGrantedFlags
import io.talkcan.mount.saf.SafMountAdapter
import io.talkcan.mount.saf.SafPersistedGrant
import io.talkcan.mount.saf.SafRequestedAccess
import io.talkcan.mount.saf.SafTakeResult
import io.talkcan.mount.saf.SafTreePickerOutcome
import io.talkcan.mount.saf.SafTreeProbe
import io.talkcan.resource.MountBinding
import io.talkcan.resource.MountBindingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2.7: Focused JVM tests for the mount selection controller.
 * Verifies cancellation/failure retain bindings, success commits and reconciles,
 * and two declarations/owners are handled independently.
 */
class MountSelectionControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val uriA = "content://com.android.externalstorage.documents/tree/primary%3AOutput"
    private val uriB = "content://com.android.externalstorage.documents/tree/primary%3ACache"

    private val declaration1 = PackageMountDeclaration(
        id = "output",
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        required = true,
        label = "Output directory",
        help = null,
    )

    private val declaration2 = PackageMountDeclaration(
        id = "cache",
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        required = true,
        label = "Cache directory",
        help = null,
    )

    private val implementationId = ChannelImplementationId("test:provider")
    private val instance1 = "instance-1"
    private val instance2 = "instance-2"

    private val grants = FakeSafGrantController()
    private lateinit var store: MountBindingStore
    private lateinit var adapter: SafMountAdapter
    private val reconciledInstances = mutableListOf<String>()
    private lateinit var controller: MountSelectionController

    private fun newController(storeFile: File = tempFolder.newFile("mount-bindings.json")): MountSelectionController {
        store = MountBindingStore(storeFile)
        assertEquals(true, (store.load() as? io.talkcan.resource.MountBindingLoadResult.Loaded) != null)
        adapter = SafMountAdapter(store, grants)
        reconciledInstances.clear()
        return MountSelectionController(adapter) { request, _ ->
            reconciledInstances += request.ownerInstanceId
        }
    }

    @Test
    fun `cancellation retains the current binding and does not reconcile`() {
        controller = newController()
        val request = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request, declaration1)

        val result = controller.complete(SafTreePickerOutcome.Cancelled)

        assertTrue(result is MountSelectionResult.RetainedPrior)
        assertNull(store.currentBinding(instance1, implementationId, declaration1.id))
        assertTrue(reconciledInstances.isEmpty())
    }

    @Test
    fun `cancellation after existing binding retains it and does not reconcile`() {
        controller = newController()
        // First bind successfully
        val request1 = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request1, declaration1)
        controller.complete(SafTreePickerOutcome.Selected(uriA))
        reconciledInstances.clear()

        // Now cancel a replacement
        val request2 = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request2, declaration1)
        val result = controller.complete(SafTreePickerOutcome.Cancelled)

        assertTrue(result is MountSelectionResult.RetainedPrior)
        val binding = store.currentBinding(instance1, implementationId, declaration1.id)
        assertTrue(binding != null)
        assertEquals(uriA, io.talkcan.mount.saf.SafGrantCodec.decode(binding!!.grant)!!.treeUri)
        assertTrue(reconciledInstances.isEmpty())
    }

    @Test
    fun `validation failure retains the current binding and does not reconcile`() {
        controller = newController()
        grants.takeResult = SafTakeResult.Failed(io.talkcan.mount.saf.SafTakeFailure.REJECTED_BY_PROVIDER)
        val request = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request, declaration1)

        val result = controller.complete(SafTreePickerOutcome.Selected(uriA))

        assertTrue(result is MountSelectionResult.Failed)
        assertEquals(MountSelectionFailure.GRANT_NOT_PERSISTED, (result as MountSelectionResult.Failed).reason)
        assertNull(store.currentBinding(instance1, implementationId, declaration1.id))
        assertTrue(reconciledInstances.isEmpty())
    }

    @Test
    fun `successful selection commits binding and triggers reconcile`() {
        controller = newController()
        val request = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request, declaration1)

        val result = controller.complete(SafTreePickerOutcome.Selected(uriA))

        assertTrue(result is MountSelectionResult.Bound)
        assertEquals(declaration1.id, (result as MountSelectionResult.Bound).declarationId)
        val binding = store.currentBinding(instance1, implementationId, declaration1.id)
        assertTrue(binding != null)
        assertEquals(uriA, io.talkcan.mount.saf.SafGrantCodec.decode(binding!!.grant)!!.treeUri)
        assertEquals(listOf(instance1), reconciledInstances)
    }

    @Test
    fun `two declarations for two owners are handled independently`() {
        controller = newController()

        // Bind output for instance-1
        val request1 = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request1, declaration1)
        controller.complete(SafTreePickerOutcome.Selected(uriA))

        // Bind cache for instance-2
        val request2 = MountSelectionRequest(instance2, implementationId, declaration2.id)
        controller.begin(request2, declaration2)
        controller.complete(SafTreePickerOutcome.Selected(uriB))

        assertEquals(listOf(instance1, instance2), reconciledInstances)

        val binding1 = store.currentBinding(instance1, implementationId, declaration1.id)
        assertTrue(binding1 != null)
        assertEquals(uriA, io.talkcan.mount.saf.SafGrantCodec.decode(binding1!!.grant)!!.treeUri)

        val binding2 = store.currentBinding(instance2, implementationId, declaration2.id)
        assertTrue(binding2 != null)
        assertEquals(uriB, io.talkcan.mount.saf.SafGrantCodec.decode(binding2!!.grant)!!.treeUri)

        // instance-1 has no cache binding, instance-2 has no output binding
        assertNull(store.currentBinding(instance1, implementationId, declaration2.id))
        assertNull(store.currentBinding(instance2, implementationId, declaration1.id))
    }

    @Test
    fun `two declarations for one owner are handled independently`() {
        controller = newController()

        // Bind output for instance-1
        val request1 = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request1, declaration1)
        controller.complete(SafTreePickerOutcome.Selected(uriA))

        // Bind cache for instance-1
        val request2 = MountSelectionRequest(instance1, implementationId, declaration2.id)
        controller.begin(request2, declaration2)
        controller.complete(SafTreePickerOutcome.Selected(uriB))

        assertEquals(listOf(instance1, instance1), reconciledInstances)

        val binding1 = store.currentBinding(instance1, implementationId, declaration1.id)
        assertTrue(binding1 != null)
        assertEquals(uriA, io.talkcan.mount.saf.SafGrantCodec.decode(binding1!!.grant)!!.treeUri)

        val binding2 = store.currentBinding(instance1, implementationId, declaration2.id)
        assertTrue(binding2 != null)
        assertEquals(uriB, io.talkcan.mount.saf.SafGrantCodec.decode(binding2!!.grant)!!.treeUri)
    }

    @Test
    fun `replacement selection commits new binding and triggers reconcile`() {
        controller = newController()

        // First bind
        val request1 = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request1, declaration1)
        controller.complete(SafTreePickerOutcome.Selected(uriA))
        reconciledInstances.clear()

        // Replace with uriB
        val request2 = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request2, declaration1)
        val result = controller.complete(SafTreePickerOutcome.Selected(uriB))

        assertTrue(result is MountSelectionResult.Bound)
        val binding = store.currentBinding(instance1, implementationId, declaration1.id)
        assertTrue(binding != null)
        assertEquals(uriB, io.talkcan.mount.saf.SafGrantCodec.decode(binding!!.grant)!!.treeUri)
        assertEquals(listOf(instance1), reconciledInstances)
    }

    @Test
    fun `complete without pending request fails gracefully`() {
        controller = newController()

        val result = controller.complete(SafTreePickerOutcome.Selected(uriA))

        assertTrue(result is MountSelectionResult.Failed)
        assertEquals(MountSelectionFailure.NO_PENDING_REQUEST, (result as MountSelectionResult.Failed).reason)
        assertTrue(reconciledInstances.isEmpty())
    }

    @Test
    fun `cancel clears pending request`() {
        controller = newController()
        val request = MountSelectionRequest(instance1, implementationId, declaration1.id)
        controller.begin(request, declaration1)
        assertEquals(request, controller.pendingRequest)

        controller.cancel()

        assertNull(controller.pendingRequest)
    }

    @Test
    fun `begin validates declaration ID matches request`() {
        controller = newController()
        val request = MountSelectionRequest(instance1, implementationId, "wrong-id")

        val exception = try {
            controller.begin(request, declaration1)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertTrue(exception != null)
        assertTrue(exception!!.message!!.contains("wrong-id"))
        assertTrue(exception.message!!.contains(declaration1.id))
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
