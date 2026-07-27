package io.talkcan.resource

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.model.ChannelImplementationId
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MountBindingUpdateRulesTest {
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

    private fun binding(
        instance: String = "instance-a",
        declarationId: String = "output",
        grantText: String = "grant-a",
        implementationId: ChannelImplementationId = implementation,
        state: MountBindingState = MountBindingState.ACTIVE,
        status: MountBindingStatus = MountBindingStatus.AVAILABLE,
    ): MountBinding = MountBinding(
        channelInstanceId = instance,
        implementationId = implementationId,
        declarationId = declarationId,
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        grant = grant(grantText),
        status = status,
        state = state,
    )

    private fun newStore(): Pair<MountBindingStore, File> {
        val dir = createTempDirectory(prefix = "mount-update-").toFile()
        val file = File(dir, "mount-bindings.json")
        val store = MountBindingStore(file)
        store.load()
        return store to file
    }

    private fun committed(store: MountBindingStore, candidate: MountBinding): MountBinding {
        val result = store.replaceBinding(
            declaration = declaration(candidate.declarationId),
            channelInstanceId = candidate.channelInstanceId,
            implementationId = candidate.implementationId,
            grant = candidate.grant,
            status = candidate.status,
        )
        return (result as MountBindingWriteResult.Committed).binding
    }

    @Test
    fun compatibleUpdateRetainsActiveBindingWithoutRetargeting() {
        val current = binding(grantText = "grant-a")
        val updated = PackageResourcesDeclaration(listOf(declaration(label = "Renamed output label")))

        val outcome = MountBindingUpdateRules.classify(implementation, updated, listOf(current))

        assertEquals(1, outcome.retained.size)
        assertTrue(outcome.dormant.isEmpty())
        val retained = outcome.retained.single()
        assertEquals(MountBindingState.ACTIVE, retained.state)
        assertEquals(current.key, retained.key)
        assertTrue(
            "Compatible update must never retarget the grant",
            current.grant.toByteArray().contentEquals(retained.grant.toByteArray()),
        )
    }

    @Test
    fun removedDeclarationMakesBindingDormantWhilePreserved() {
        val current = binding(grantText = "grant-a")
        val outcome = MountBindingUpdateRules.classify(
            implementation,
            PackageResourcesDeclaration(emptyList()),
            listOf(current),
        )

        assertTrue(outcome.retained.isEmpty())
        val dormant = outcome.dormant.single()
        assertEquals(MountBindingState.DORMANT, dormant.state)
        assertTrue(
            "Dormant binding must preserve the original grant for rollback",
            current.grant.toByteArray().contentEquals(dormant.grant.toByteArray()),
        )
    }

    @Test
    fun classifyRejectsForeignImplementationBindings() {
        val foreign = binding(implementationId = otherImplementation)
        try {
            MountBindingUpdateRules.classify(implementation, PackageResourcesDeclaration(emptyList()), listOf(foreign))
            throw AssertionError("Classifying a foreign-implementation binding must fail")
        } catch (expected: IllegalArgumentException) {
            // Expected: classification is scoped to exactly one implementation.
        }
    }

    @Test
    fun compatibilityClassificationIsPure() {
        val current = binding()
        assertEquals(
            MountBindingCompatibility.Compatible,
            current.compatibilityWith(implementation, declaration()),
        )
        assertEquals(
            MountBindingCompatibility.Incompatible.DeclarationRemoved,
            current.compatibilityWith(implementation, null),
        )
        assertEquals(
            MountBindingCompatibility.Incompatible.ForeignImplementation(otherImplementation, implementation),
            binding(implementationId = otherImplementation).compatibilityWith(implementation, declaration()),
        )
    }

    @Test
    fun storeAppliesUpdateDormancyAndRollbackRestoresActivity() {
        val (store, file) = newStore()
        val original = committed(store, binding(grantText = "grant-a"))
        val beforeUpdate = file.readBytes()

        // Update removes the declaration: binding goes dormant, grant preserved.
        val dormantOutcome = MountBindingUpdateRules.classify(
            implementation,
            PackageResourcesDeclaration(emptyList()),
            store.bindings().filter { it.implementationId == implementation },
        )
        val applied = store.applyUpdate(dormantOutcome) as? MountBindingUpdateResult.Committed
            ?: throw AssertionError("applyUpdate must commit dormancy")
        val dormant = applied.snapshot.single()
        assertEquals(MountBindingState.DORMANT, dormant.state)
        assertTrue(original.grant.toByteArray().contentEquals(dormant.grant.toByteArray()))
        assertTrue(
            "Dormant binding must remain persisted for rollback",
            store.bindingsForInstance("instance-a").single().state == MountBindingState.DORMANT,
        )
        assertTrue(
            MountAvailabilityProjection.project(implementation, declaration(), dormant)
                is MountAvailability.Unavailable,
        )

        // Rollback to a revision declaring the mount again: binding reactivates.
        val rollbackOutcome = MountBindingUpdateRules.classify(
            implementation,
            PackageResourcesDeclaration(listOf(declaration())),
            store.bindings().filter { it.implementationId == implementation },
        )
        val restored = store.applyUpdate(rollbackOutcome) as? MountBindingUpdateResult.Committed
            ?: throw AssertionError("applyUpdate must commit rollback")
        val active = restored.snapshot.single()
        assertEquals(MountBindingState.ACTIVE, active.state)
        assertTrue(original.grant.toByteArray().contentEquals(active.grant.toByteArray()))
        assertTrue(MountAvailabilityProjection.project(implementation, declaration(), active) is MountAvailability.Available)

        val restarted = MountBindingStore(file)
        restarted.load()
        assertEquals(active, restarted.currentBinding("instance-a", implementation, "output"))
        assertArrayEquals(
            "Rollback to the original state must reproduce the original document bytes",
            beforeUpdate,
            file.readBytes(),
        )
    }

    @Test
    fun applyUpdateIsUnchangedWhenClassificationMatchesCurrentState() {
        val (store, file) = newStore()
        committed(store, binding(grantText = "grant-a"))
        val beforeBytes = file.readBytes()

        val outcome = MountBindingUpdateRules.classify(
            implementation,
            PackageResourcesDeclaration(listOf(declaration())),
            store.bindings().filter { it.implementationId == implementation },
        )
        assertEquals(MountBindingUpdateResult.Unchanged, store.applyUpdate(outcome))
        assertArrayEquals("Unchanged classification must not rewrite the document", beforeBytes, file.readBytes())
    }

    @Test
    fun applyUpdateRejectsUnknownBindingKeys() {
        val (store, _) = newStore()
        val ghost = binding(instance = "no-such-instance")
        val outcome = MountBindingUpdateRules.classify(
            implementation,
            PackageResourcesDeclaration(listOf(declaration())),
            listOf(ghost),
        )
        val failed = store.applyUpdate(outcome) as? MountBindingUpdateResult.Failed
            ?: throw AssertionError("Applying an unknown key must fail")
        assertEquals(MountBindingFailure.BindingNotFound(ghost.key), failed.failure)
    }

    @Test
    fun updateClassificationLeavesOtherImplementationsUntouched() {
        val (store, _) = newStore()
        committed(store, binding(grantText = "grant-a"))
        committed(store, binding(implementationId = otherImplementation, grantText = "grant-b"))

        val outcome = MountBindingUpdateRules.classify(
            implementation,
            PackageResourcesDeclaration(emptyList()),
            store.bindings().filter { it.implementationId == implementation },
        )
        val applied = store.applyUpdate(outcome) as? MountBindingUpdateResult.Committed
            ?: throw AssertionError("applyUpdate must commit")

        val statesByKey = applied.snapshot.associate { it.key to it.state }
        assertEquals(
            MountBindingState.DORMANT,
            statesByKey[MountBindingKey("instance-a", implementation, "output")],
        )
        assertEquals(
            MountBindingState.ACTIVE,
            statesByKey[MountBindingKey("instance-a", otherImplementation, "output")],
        )
    }

    @Test
    fun availabilityProjectionCoversEveryTypedReason() {
        assertTrue(
            MountAvailabilityProjection.project(implementation, declaration(), null)
                .let { it is MountAvailability.Unavailable && it.reason == MountUnavailableReason.Unbound },
        )
        assertTrue(
            MountAvailabilityProjection.project(implementation, declaration(), binding(status = MountBindingStatus.AVAILABLE))
                is MountAvailability.Available,
        )
        assertTrue(
            MountAvailabilityProjection.project(implementation, declaration(), binding(status = MountBindingStatus.READ_ONLY))
                .let { it is MountAvailability.Unavailable && it.reason == MountUnavailableReason.ReadOnly },
        )
        assertTrue(
            MountAvailabilityProjection.project(implementation, declaration(), binding(status = MountBindingStatus.NEEDS_REAUTHORIZATION))
                .let {
                    it is MountAvailability.Unavailable &&
                        it.reason == MountUnavailableReason.NeedsReauthorization
                },
        )
        assertTrue(
            MountAvailabilityProjection.project(implementation, declaration(), binding(status = MountBindingStatus.UNAVAILABLE))
                .let { it is MountAvailability.Unavailable && it.reason == MountUnavailableReason.GrantUnavailable },
        )
        assertTrue(
            MountAvailabilityProjection.project(
                implementation,
                declaration(),
                binding(status = MountBindingStatus.UNAVAILABLE, state = MountBindingState.DORMANT),
            ).let { it is MountAvailability.Unavailable && it.reason == MountUnavailableReason.Dormant },
        )
        val foreign = MountAvailabilityProjection.project(implementation, declaration(), binding(implementationId = otherImplementation))
        assertTrue(
            foreign is MountAvailability.Unavailable &&
                foreign.reason is MountUnavailableReason.IncompatibleDeclaration,
        )
    }
}
