package io.talkcan.resource

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.model.ChannelImplementationId
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MountBindingStoreTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val implementation = ChannelImplementationId("github-repository:123456")
    private val otherImplementation = ChannelImplementationId("builtin:debug")

    private fun declaration(id: String = "output", label: String = "Output directory") =
        PackageMountDeclaration(
            id = id,
            kind = PackageMountKind.DIRECTORY_TREE,
            access = PackageMountAccess.READ_WRITE,
            required = true,
            label = label,
            help = null,
        )

    private fun grant(text: String) = PlatformGrantBlob(text.toByteArray(Charsets.UTF_8))

    private fun newStore(): Pair<MountBindingStore, File> {
        val dir = createTempDirectory(prefix = "mount-bindings-").toFile()
        val file = File(dir, "mount-bindings.json")
        val store = MountBindingStore(file)
        val loaded = store.load()
        assertTrue("Fresh store must load empty: $loaded", loaded is MountBindingLoadResult.Loaded)
        return store to file
    }

    private fun committed(
        store: MountBindingStore,
        instance: String = "instance-a",
        declaration: PackageMountDeclaration = declaration(),
        grantText: String = "grant-a",
        implementationId: ChannelImplementationId = implementation,
        status: MountBindingStatus = MountBindingStatus.AVAILABLE,
    ): MountBinding {
        val result = store.replaceBinding(
            declaration = declaration,
            channelInstanceId = instance,
            implementationId = implementationId,
            grant = grant(grantText),
            status = status,
        )
        val committed = result as? MountBindingWriteResult.Committed
            ?: throw AssertionError("replaceBinding must commit: $result")
        return committed.binding
    }

    @Test
    fun restartRoundTripRestoresIndependentBindingsExactly() {
        val (store, file) = newStore()
        val first = committed(store, instance = "instance-a", grantText = "grant-a")
        val second = committed(store, instance = "instance-b", grantText = "grant-b")

        val restarted = MountBindingStore(file)
        val loaded = restarted.load() as? MountBindingLoadResult.Loaded
            ?: throw AssertionError("Restart must load")

        assertEquals(listOf(first, second), loaded.bindings)
        assertEquals(listOf(first, second), restarted.bindings())
        assertEquals(first, restarted.currentBinding("instance-a", implementation, "output"))
        assertEquals(second, restarted.currentBinding("instance-b", implementation, "output"))
    }

    @Test
    fun identicalContentCommitsProduceByteIdenticalDocuments() {
        val (storeA, fileA) = newStore()
        committed(storeA, instance = "instance-a", grantText = "grant-a")
        committed(storeA, instance = "instance-b", grantText = "grant-b")

        val (storeB, fileB) = newStore()
        committed(storeB, instance = "instance-b", grantText = "grant-b")
        committed(storeB, instance = "instance-a", grantText = "grant-a")

        assertArrayEquals(
            "Deterministic encoding must not depend on commit order",
            fileA.readBytes(),
            fileB.readBytes(),
        )
    }

    @Test
    fun instancesAreIsolatedUnderSharedProviderAndDeclaration() {
        val (store, _) = newStore()
        val a = committed(store, instance = "instance-a", grantText = "grant-a")
        val b = committed(store, instance = "instance-b", grantText = "grant-b")

        assertEquals(listOf(a), store.bindingsForInstance("instance-a"))
        assertEquals(listOf(b), store.bindingsForInstance("instance-b"))

        val removed = store.removeInstance("instance-a") as? MountBindingRemovalResult.Committed
            ?: throw AssertionError("removeInstance must commit")
        assertEquals(listOf(a), removed.removal.removed)
        assertEquals(listOf(a.grant), removed.removal.unreferencedGrants)
        assertTrue(removed.removal.stillReferencedGrants.isEmpty())

        assertEquals(listOf(b), store.bindings())
        assertNull(store.currentBinding("instance-a", implementation, "output"))
    }

    @Test
    fun failedCandidateValidationPreservesActiveBinding() {
        val (store, file) = newStore()
        val original = committed(store, grantText = "grant-a")
        val beforeBytes = file.readBytes()

        val rejected = store.replaceBinding(
            declaration = declaration(),
            channelInstanceId = "instance-a",
            implementationId = implementation,
            grant = grant("grant-b"),
            status = MountBindingStatus.AVAILABLE,
            candidateValidator = MountBindingCandidateValidator {
                MountBindingCandidateOutcome.Rejected("selected tree is not writable")
            },
        )
        val failure = rejected as? MountBindingWriteResult.Failed
            ?: throw AssertionError("Rejected candidate must fail: $rejected")
        val candidateRejected = failure.failure as? MountBindingFailure.CandidateRejected
            ?: throw AssertionError("Expected CandidateRejected, got ${failure.failure}")
        assertEquals("selected tree is not writable", candidateRejected.reason)

        assertEquals(original, store.currentBinding("instance-a", implementation, "output"))
        assertArrayEquals("Rejected candidate must not touch the persisted document", beforeBytes, file.readBytes())

        val restarted = MountBindingStore(file)
        restarted.load()
        assertEquals(original, restarted.currentBinding("instance-a", implementation, "output"))
    }

    @Test
    fun writeFailurePreservesActiveBinding() {
        val (store, file) = newStore()
        val original = committed(store, grantText = "grant-a")
        val dir = file.parentFile!!
        val beforeBytes = file.readBytes()

        assertTrue(dir.setWritable(false))
        try {
            val failed = store.replaceBinding(
                declaration = declaration(),
                channelInstanceId = "instance-a",
                implementationId = implementation,
                grant = grant("grant-b"),
                status = MountBindingStatus.AVAILABLE,
            )
            val failure = failed as? MountBindingWriteResult.Failed
                ?: throw AssertionError("Write to read-only directory must fail: $failed")
            assertTrue(
                "Expected WriteFailed, got ${failure.failure}",
                failure.failure is MountBindingFailure.WriteFailed
            )
        } finally {
            assertTrue(dir.setWritable(true))
        }

        assertEquals(
            "In-memory state must keep the prior binding after write failure",
            original,
            store.currentBinding("instance-a", implementation, "output"),
        )

        val restarted = MountBindingStore(file)
        restarted.load()
        assertEquals(original, restarted.currentBinding("instance-a", implementation, "output"))
    }

    @Test
    fun corruptPersistedDocumentRefusesImplicitOverwrite() {
        val dir = createTempDirectory(prefix = "mount-bindings-").toFile()
        val file = File(dir, "mount-bindings.json")
        file.writeText("{\"version\": 1, \"bindings\": [corrupt", Charsets.UTF_8)

        val store = MountBindingStore(file)
        val loaded = store.load()
        assertTrue("Corrupt document must report Corrupt: $loaded", loaded is MountBindingLoadResult.Corrupt)

        val refused = store.replaceBinding(
            declaration = declaration(),
            channelInstanceId = "instance-a",
            implementationId = implementation,
            grant = grant("grant-a"),
            status = MountBindingStatus.AVAILABLE,
        )
        val failure = refused as? MountBindingWriteResult.Failed
            ?: throw AssertionError("Unloaded store must refuse mutations: $refused")
        assertEquals(MountBindingFailure.NotLoaded, failure.failure)
        assertNull(store.currentBinding("instance-a", implementation, "output"))
        assertEquals(
            "Corrupt document must not be overwritten by an implicit empty state",
            "{\"version\": 1, \"bindings\": [corrupt",
            file.readText(Charsets.UTF_8),
        )

        val (recovered, _) = newStore()
        val committedBinding = committed(recovered, grantText = "grant-a")
        file.writeText(MountBindingCodec.encode(recovered.bindings()), Charsets.UTF_8)
        val reloaded = MountBindingStore(file)
        reloaded.load()
        assertEquals(committedBinding, reloaded.currentBinding("instance-a", implementation, "output"))
    }

    @Test
    fun sharedGrantIsNotReleasedWhileAnotherBindingReferencesIt() {
        val (store, _) = newStore()
        committed(store, instance = "instance-a", grantText = "shared-grant")
        committed(store, instance = "instance-b", grantText = "shared-grant")

        val first = store.removeBinding("instance-a", implementation, "output")
            as? MountBindingRemovalResult.Committed
            ?: throw AssertionError("removeBinding must commit")
        assertTrue(
            "Grant still referenced by instance-b must not be released",
            first.removal.unreferencedGrants.isEmpty(),
        )
        assertEquals(listOf(grant("shared-grant")), first.removal.stillReferencedGrants)

        val second = store.removeInstance("instance-b")
            as? MountBindingRemovalResult.Committed
            ?: throw AssertionError("removeInstance must commit")
        assertEquals(listOf(grant("shared-grant")), second.removal.unreferencedGrants)
        assertTrue(second.removal.stillReferencedGrants.isEmpty())
        assertTrue(store.bindings().isEmpty())
    }

    @Test
    fun distinctGrantsOfOneInstanceAllBecomeUnreferenced() {
        val (store, _) = newStore()
        committed(store, instance = "instance-a", declaration = declaration("output"), grantText = "grant-a")
        committed(store, instance = "instance-a", declaration = declaration("cache"), grantText = "grant-b")

        val removed = store.removeInstance("instance-a") as? MountBindingRemovalResult.Committed
            ?: throw AssertionError("removeInstance must commit")
        assertEquals(2, removed.removal.removed.size)
        assertEquals(listOf(grant("grant-a"), grant("grant-b")), removed.removal.unreferencedGrants)
        assertTrue(removed.removal.stillReferencedGrants.isEmpty())
    }

    @Test
    fun removalOfMissingBindingMakesNoWrite() {
        val (store, file) = newStore()
        committed(store, grantText = "grant-a")
        val beforeBytes = file.readBytes()

        assertEquals(MountBindingRemovalResult.Unchanged, store.removeInstance("no-such-instance"))
        assertEquals(
            MountBindingRemovalResult.Unchanged,
            store.removeBinding("instance-a", implementation, "missing"),
        )
        assertArrayEquals(beforeBytes, file.readBytes())
    }

    @Test
    fun statusUpdatePersistsAcrossRestartAndPreservesGrant() {
        val (store, file) = newStore()
        val original = committed(store, grantText = "grant-a")

        val updated = store.updateStatus("instance-a", implementation, "output",
            MountBindingStatus.NEEDS_REAUTHORIZATION)
            as? MountBindingStatusResult.Committed
            ?: throw AssertionError("updateStatus must commit")
        assertEquals(original, updated.previous)
        assertEquals(MountBindingStatus.NEEDS_REAUTHORIZATION, updated.binding.status)
        assertTrue(original.grant.toByteArray().contentEquals(updated.binding.grant.toByteArray()))

        assertEquals(
            MountBindingStatusResult.Unchanged,
            store.updateStatus("instance-a", implementation, "output", MountBindingStatus.NEEDS_REAUTHORIZATION),
        )

        val restarted = MountBindingStore(file)
        restarted.load()
        assertEquals(updated.binding, restarted.currentBinding("instance-a", implementation, "output"))
    }

    @Test
    fun replacementReportsDisplacedBindingForReferenceAwareRelease() {
        val (store, _) = newStore()
        committed(store, grantText = "grant-a")

        val replaced = store.replaceBinding(
            declaration = declaration(),
            channelInstanceId = "instance-a",
            implementationId = implementation,
            grant = grant("grant-b"),
            status = MountBindingStatus.AVAILABLE,
        ) as? MountBindingWriteResult.Committed
            ?: throw AssertionError("Replacement must commit")

        assertEquals("grant-a", replaced.replaced?.grant?.let { String(it.toByteArray(), Charsets.UTF_8) })
        assertEquals("grant-b", String(replaced.binding.grant.toByteArray(), Charsets.UTF_8))

        val removal = mountBindingRemoval(listOfNotNull(replaced.replaced), replaced.snapshot)
        assertEquals(
            "Displaced grant no longer referenced is release-eligible",
            listOf(grant("grant-a")),
            removal.unreferencedGrants,
        )
        assertTrue(removal.stillReferencedGrants.isEmpty())
    }

    @Test
    fun replacementWithSameGrantKeepsGrantReferenced() {
        val (store, _) = newStore()
        committed(store, grantText = "grant-a")

        val replaced = store.replaceBinding(
            declaration = declaration(),
            channelInstanceId = "instance-a",
            implementationId = implementation,
            grant = grant("grant-a"),
            status = MountBindingStatus.AVAILABLE,
        ) as? MountBindingWriteResult.Committed
            ?: throw AssertionError("Replacement must commit")

        val removal = mountBindingRemoval(listOfNotNull(replaced.replaced), replaced.snapshot)
        assertTrue(
            "Same grant re-bound must stay referenced",
            removal.unreferencedGrants.isEmpty(),
        )
        assertEquals(listOf(grant("grant-a")), removal.stillReferencedGrants)
    }

    @Test
    fun mutationsRequireSuccessfulLoad() {
        val dir = createTempDirectory(prefix = "mount-bindings-").toFile()
        val store = MountBindingStore(File(dir, "mount-bindings.json"))

        val failed = store.replaceBinding(
            declaration = declaration(),
            channelInstanceId = "instance-a",
            implementationId = implementation,
            grant = grant("grant-a"),
            status = MountBindingStatus.AVAILABLE,
        )
        val failure = failed as? MountBindingWriteResult.Failed
            ?: throw AssertionError("Unloaded store must fail: $failed")
        assertEquals(MountBindingFailure.NotLoaded, failure.failure)

        val status = store.updateStatus("instance-a", implementation, "output", MountBindingStatus.AVAILABLE)
        assertEquals(
            MountBindingFailure.NotLoaded,
            (status as MountBindingStatusResult.Failed).failure,
        )
        val removal = store.removeInstance("instance-a")
        val removalFailure = removal as? MountBindingRemovalResult.Failed
            ?: throw AssertionError("Unloaded store must fail removal: $removal")
        assertEquals(MountBindingFailure.NotLoaded, removalFailure.failure)
    }

    @Test
    fun foreignImplementationBindingsCoexistUnderSameInstanceAndDeclaration() {
        val (store, _) = newStore()
        val external = committed(store, grantText = "grant-a", implementationId = implementation)
        val builtin = committed(store, grantText = "grant-b", implementationId = otherImplementation)

        assertEquals(external, store.currentBinding("instance-a", implementation, "output"))
        assertEquals(builtin, store.currentBinding("instance-a", otherImplementation, "output"))
        assertEquals(listOf(builtin, external), store.bindingsForInstance("instance-a"))
    }
}
