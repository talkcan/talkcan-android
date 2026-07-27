package io.talkcan.dependency

import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.OpaqueAudioRecording
import io.talkcan.channel.capability.Transcription
import io.talkcan.channel.capability.TranscriptionCapability
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.mount.saf.SafGrantedFlags
import io.talkcan.mount.saf.SafGrantCodec
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.mount.saf.SafMountAdapter
import io.talkcan.mount.saf.SafPersistedGrant
import io.talkcan.mount.saf.SafRequestedAccess
import io.talkcan.mount.saf.SafTakeFailure
import io.talkcan.mount.saf.SafTakeResult
import io.talkcan.mount.saf.SafTreePickerOutcome
import io.talkcan.mount.saf.SafTreeProbe
import io.talkcan.resource.MountAvailability
import io.talkcan.resource.MountAvailabilityProjection
import io.talkcan.resource.MountBindingCompatibility
import io.talkcan.resource.MountBindingState
import io.talkcan.resource.MountBindingStore
import io.talkcan.resource.MountBindingUpdateResult
import io.talkcan.resource.compatibilityWith
import io.talkcan.resource.MountBindingUpdateRules
import io.talkcan.resource.MountUnavailableReason
import io.talkcan.resource.compatibilityWith
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelRuntimeRegistry
import io.talkcan.service.ChannelRuntimeRegistryShutdownResult
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeInvocationPolicy
import io.talkcan.service.RuntimeWorkerDispatcher
import io.talkcan.ui.MountEditorEntry
import io.talkcan.ui.MountEditorProjection
import io.talkcan.ui.MountSelectionController
import io.talkcan.ui.MountSelectionRequest
import io.talkcan.ui.MountSelectionResult
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasks 10.2–10.4 (+2.9) for the byte-pinned external Journal package.
 *
 * Every test starts from the exact published v1.1.0 fixture and traverses the
 * generic host machinery only — validator → installed store → materializer →
 * provider registry → catalogue → runtime registry, with output trees bound
 * through the generic resource UI (mount selection controller → SAF adapter →
 * transactional binding store). There is no Journal-specific test provider, no
 * provider-name dispatch, and no capability-port shortcut: the package is
 * installed and exercised exactly as an external repository package would be.
 *
 * Native Lua execution is unavailable to JVM unit tests, so [JournalKernelBridge]
 * is confined to the native kernel boundary: it retains per-state ownership,
 * exposes the generic callback protocol, and records only values crossing that
 * boundary (created/closed states and startup `output_mode`).
 *
 * Scope note: Lua-side readiness gating on mount availability is task 7.3
 * (readiness-context resource status) and is intentionally NOT exercised here;
 * `activate()` publishes `Available` after a successful startup independent of
 * bindings. The generic editor/availability gate that blocks an instance whose
 * required mount is unbound IS exercised through [MountEditorProjection].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalJournalChannelLifecycleContractTest {

    // ── 10.2 ──────────────────────────────────────────────────────────────
    @Test
    fun `two external instances bind distinct output trees through the generic resource UI`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = JournalKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)

            // The external Journal provider is repository-derived, never built-in.
            assertEquals("github-repository:$REPOSITORY_ID", id.value)
            assertFalse(
                "External Journal must not be a built-in channel",
                id.value.startsWith("builtin:"),
            )

            val store = newStore(root)
            val descriptor = availableDescriptor(providers, id)
            val outputDeclaration = descriptor.resourceDeclarations.mounts.single()
            assertEquals("output", outputDeclaration.id)

            val registry = runtimeRegistry(providers, JournalCapabilityHost())
            val instanceA = definition("journal-a", id, "VOICE")
            val instanceB = definition("journal-b", id, "TRANSCRIPT")
            val snapshot = ChannelCatalogueSnapshot(listOf(instanceA, instanceB), instanceA.id)
            registry.reconcile(snapshot)
            advanceUntilIdle()

            // Two distinct channel instances → two distinct Lua states, both started.
            assertEquals(2, bridge.createdStates.size)
            assertNotEquals(bridge.createdStates[0], bridge.createdStates[1])
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceB.id)?.preparation)

            // The generic editor blocks each instance while its required mount is unbound.
            assertBlockingOutputMount(providers, store, instanceA.id, id)
            assertBlockingOutputMount(providers, store, instanceB.id, id)

            // Bind distinct output trees through the generic resource UI action/result flow.
            val reconciled = mutableListOf<String>()
            val controller = newController(store, reconciled)
            bindOutput(controller, instanceA.id, id, outputDeclaration, URI_A)
            bindOutput(controller, instanceB.id, id, outputDeclaration, URI_B)
            assertEquals(listOf(instanceA.id, instanceB.id), reconciled)

            // Two distinct opaque bindings, keyed per instance, carrying distinct grants.
            val bindingA = store.currentBinding(instanceA.id, id, "output")
            val bindingB = store.currentBinding(instanceB.id, id, "output")
            assertNotNull(bindingA)
            assertNotNull(bindingB)
            assertEquals(URI_A, SafGrantCodec.decode(bindingA!!.grant)!!.treeUri)
            assertEquals(URI_B, SafGrantCodec.decode(bindingB!!.grant)!!.treeUri)
            assertNotEquals("Distinct instances must hold distinct opaque grants", bindingA.grant, bindingB.grant)
            assertEquals(MountBindingState.ACTIVE, bindingA.state)
            assertEquals(MountBindingState.ACTIVE, bindingB.state)
            // No cross-binding: A has no B grant and vice versa.
            assertNull(store.currentBinding(instanceA.id, id, "other"))
            assertEquals(2, store.bindings().size)

            // Once bound, the required mount no longer blocks either instance.
            assertClearOutputMount(providers, store, instanceA.id, id)
            assertClearOutputMount(providers, store, instanceB.id, id)

            // The distinct scalar output_mode reached each instance's startup callback.
            assertTrue("VOICE scalar must reach instance A startup", bridge.startupModes.contains("VOICE"))
            assertTrue("TRANSCRIPT scalar must reach instance B startup", bridge.startupModes.contains("TRANSCRIPT"))

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    // ── 10.3 scalar ───────────────────────────────────────────────────────
    @Test
    fun `scalar output mode edit replaces exactly one generation and revokes the predecessor`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = JournalKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val registry = runtimeRegistry(providers, JournalCapabilityHost())

            val instanceA = definition("journal-a", id, "VOICE")
            val instanceB = definition("journal-b", id, "TRANSCRIPT")
            registry.reconcile(ChannelCatalogueSnapshot(listOf(instanceA, instanceB), instanceA.id))
            advanceUntilIdle()
            assertEquals(2, bridge.createdStates.size)
            assertEquals(0, bridge.closedStates.size)
            val createdBefore = bridge.createdStates.size
            val closedBefore = bridge.closedStates.size

            // Scalar-only edit on instance A: a fresh configuration payload, same identity.
            val editedA = instanceA.copy(
                configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("output_mode", "VOICE_AND_TRANSCRIPT")),
            )
            registry.reconcile(ChannelCatalogueSnapshot(listOf(editedA, instanceB), editedA.id))
            advanceUntilIdle()

            // Exactly one fresh generation; exactly one predecessor revoked.
            assertEquals("Scalar edit must create exactly one new generation", createdBefore + 1, bridge.createdStates.size)
            assertEquals("Scalar edit must revoke exactly one predecessor", closedBefore + 1, bridge.closedStates.size)
            // Both instances remain available; sibling generation is untouched.
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(editedA.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceB.id)?.preparation)
            // The successor started with the edited scalar value.
            assertEquals("VOICE_AND_TRANSCRIPT", bridge.startupModes.last())

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    // ── 10.3 mount replacement ────────────────────────────────────────────
    @Test
    fun `mount replacement creates one fresh generation after predecessor authority revoked`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = JournalKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val store = newStore(root)
            val outputDeclaration = availableDescriptor(providers, id).resourceDeclarations.mounts.single()
            val registry = runtimeRegistry(providers, JournalCapabilityHost())

            val instanceA = definition("journal-a", id, "VOICE")
            val instanceB = definition("journal-b", id, "TRANSCRIPT")
            val snapshot = ChannelCatalogueSnapshot(listOf(instanceA, instanceB), instanceA.id)
            registry.reconcile(snapshot)
            advanceUntilIdle()

            val reconciled = mutableListOf<String>()
            val controller = newController(store, reconciled)
            bindOutput(controller, instanceA.id, id, outputDeclaration, URI_A)
            bindOutput(controller, instanceB.id, id, outputDeclaration, URI_B)
            // Bring both instances to their bound generation, as the service composition does on bind.
            registry.reconcileResourceBinding(snapshot, instanceA.id)
            advanceUntilIdle()
            registry.reconcileResourceBinding(snapshot, instanceB.id)
            advanceUntilIdle()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceB.id)?.preparation)

            val createdBefore = bridge.createdStates.size
            val closedBefore = bridge.closedStates.size
            val grantBefore = store.currentBinding(instanceA.id, id, "output")!!.grant

            // Replace instance A's output tree through the generic UI; the definition is unchanged.
            bindOutput(controller, instanceA.id, id, outputDeclaration, URI_C)
            assertEquals(URI_C, SafGrantCodec.decode(store.currentBinding(instanceA.id, id, "output")!!.grant)!!.treeUri)

            // The binding replacement drives exactly one atomic fresh generation for A only.
            registry.reconcileResourceBinding(snapshot, instanceA.id)
            advanceUntilIdle()

            assertEquals("Mount replacement must create exactly one new generation", createdBefore + 1, bridge.createdStates.size)
            assertEquals("Mount replacement must revoke exactly one predecessor", closedBefore + 1, bridge.closedStates.size)
            assertNotEquals("Replacement must commit a distinct opaque grant", grantBefore, store.currentBinding(instanceA.id, id, "output")!!.grant)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)
            assertEquals("Sibling instance must remain available", ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceB.id)?.preparation)
            // Sibling binding is untouched by A's replacement.
            assertEquals(URI_B, SafGrantCodec.decode(store.currentBinding(instanceB.id, id, "output")!!.grant)!!.treeUri)

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    // ── 10.3 failure preserves prior state ────────────────────────────────
    @Test
    fun `failed or cancelled selection preserves the prior binding and live generation`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = JournalKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val store = newStore(root)
            val grants = FakeSafGrantController()
            val outputDeclaration = availableDescriptor(providers, id).resourceDeclarations.mounts.single()
            val registry = runtimeRegistry(providers, JournalCapabilityHost())

            val instanceA = definition("journal-a", id, "VOICE")
            val snapshot = ChannelCatalogueSnapshot(listOf(instanceA), instanceA.id)
            registry.reconcile(snapshot)
            advanceUntilIdle()

            val reconciled = mutableListOf<String>()
            val controller = MountSelectionController(SafMountAdapter(store, grants)) { request, _ ->
                reconciled += request.ownerInstanceId
            }
            bindOutput(controller, instanceA.id, id, outputDeclaration, URI_A)
            registry.reconcileResourceBinding(snapshot, instanceA.id)
            advanceUntilIdle()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)

            val createdBefore = bridge.createdStates.size
            val closedBefore = bridge.closedStates.size
            val grantBefore = store.currentBinding(instanceA.id, id, "output")!!.grant
            reconciled.clear()

            // A validation failure retains the prior binding and creates no generation.
            grants.takeResult = SafTakeResult.Failed(SafTakeFailure.REJECTED_BY_PROVIDER)
            controller.begin(MountSelectionRequest(instanceA.id, id, "output"), outputDeclaration)
            val failed = controller.complete(SafTreePickerOutcome.Selected(URI_C))
            assertTrue(failed is MountSelectionResult.Failed)
            assertTrue("Failed selection must not reconcile", reconciled.isEmpty())
            assertEquals("Failed selection must preserve the prior grant", grantBefore, store.currentBinding(instanceA.id, id, "output")!!.grant)
            assertEquals("Failed selection must create no generation", createdBefore, bridge.createdStates.size)
            assertEquals("Failed selection must revoke no predecessor", closedBefore, bridge.closedStates.size)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)

            // A cancellation likewise retains the prior binding and live generation.
            grants.takeResult = SafTakeResult.Taken(SafGrantedFlags(read = true, write = true))
            controller.begin(MountSelectionRequest(instanceA.id, id, "output"), outputDeclaration)
            val cancelled = controller.complete(SafTreePickerOutcome.Cancelled)
            assertTrue(cancelled is MountSelectionResult.RetainedPrior)
            assertTrue("Cancelled selection must not reconcile", reconciled.isEmpty())
            assertEquals("Cancelled selection must preserve the prior grant", grantBefore, store.currentBinding(instanceA.id, id, "output")!!.grant)
            assertEquals("Cancelled selection must create no generation", createdBefore, bridge.createdStates.size)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    // ── 10.4 compatible update / rollback ─────────────────────────────────
    @Test
    fun `compatible update and rollback retain bindings with a fresh revision and identity intact`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = JournalKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val store = newStore(root)
            val outputDeclaration = availableDescriptor(providers, id).resourceDeclarations.mounts.single()
            val registry = runtimeRegistry(providers, JournalCapabilityHost())

            val instanceA = definition("journal-a", id, "VOICE")
            val snapshot = ChannelCatalogueSnapshot(listOf(instanceA), instanceA.id)
            registry.reconcile(snapshot)
            advanceUntilIdle()
            val reconciled = mutableListOf<String>()
            val controller = newController(store, reconciled)
            bindOutput(controller, instanceA.id, id, outputDeclaration, URI_A)
            registry.reconcileResourceBinding(snapshot, instanceA.id)
            advanceUntilIdle()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)

            val grantBefore = store.currentBinding(instanceA.id, id, "output")!!.grant
            val fingerprintBefore = (providers.resolve(id) as ChannelProviderResolution.Available).provider.fingerprint
            val bindingBytesBefore = storeFile(root).readBytes()

            // Compatible successor: identical mounts/identity, only packageVersion advanced.
            val repo = repository(root, bridge, providers)
            assertEquals(
                MutationResult.Updated(id),
                success(repo.installOrUpdate(ByteArrayInputStream(successorFixture()), successorSourceRecord())),
            )
            assertTrue(providers.resolve(id) is ChannelProviderResolution.Available)
            val fingerprintAfter = (providers.resolve(id) as ChannelProviderResolution.Available).provider.fingerprint
            assertNotEquals("Compatible update must publish a fresh immutable revision", fingerprintBefore, fingerprintAfter)
            assertEquals("Provider identity must never be rewritten", "github-repository:$REPOSITORY_ID", id.value)

            // Compatible binding classification: retained active, grant untouched, byte-for-byte.
            val updatedDeclarations = availableDescriptor(providers, id).resourceDeclarations
            assertEquals(listOf("output"), updatedDeclarations.mounts.map { it.id })
            val updateOutcome = MountBindingUpdateRules.classify(
                id,
                updatedDeclarations,
                store.bindings().filter { it.implementationId == id },
            )
            assertEquals(1, updateOutcome.retained.size)
            assertTrue(updateOutcome.dormant.isEmpty())
            assertEquals(MountBindingUpdateResult.Unchanged, store.applyUpdate(updateOutcome))
            assertArrayEqualsMsg("Compatible update must not rewrite the binding document", bindingBytesBefore, storeFile(root).readBytes())
            val retained = store.currentBinding(instanceA.id, id, "output")!!
            assertEquals(MountBindingState.ACTIVE, retained.state)
            assertEquals("output", retained.declarationId)
            assertEquals(id, retained.implementationId)
            assertEquals("Binding grant must be retained, not retargeted", grantBefore, retained.grant)

            // Re-reconciliation after the revision change spawns exactly one fresh generation.
            val createdBefore = bridge.createdStates.size
            val closedBefore = bridge.closedStates.size
            registry.reconcile(snapshot)
            advanceUntilIdle()
            assertEquals(createdBefore + 1, bridge.createdStates.size)
            assertEquals(closedBefore + 1, bridge.closedStates.size)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)

            // Explicit rollback restores the exact v1.1.0 revision and retains the binding.
            assertEquals(MutationResult.RolledBack(id), success(repo.rollback(sourceRecord().repositoryId)))
            val rolledBack = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(sourceRecord().repositoryId)
            assertEquals(PACKAGE_VERSION, rolledBack.active.manifest.packageVersion)
            assertEquals("1.0.2", rolledBack.rollback?.manifest?.packageVersion)
            val fingerprintRolledBack = (providers.resolve(id) as ChannelProviderResolution.Available).provider.fingerprint
            assertEquals("Rollback must restore the exact original revision", fingerprintBefore, fingerprintRolledBack)

            val rollbackOutcome = MountBindingUpdateRules.classify(
                id,
                availableDescriptor(providers, id).resourceDeclarations,
                store.bindings().filter { it.implementationId == id },
            )
            assertEquals(1, rollbackOutcome.retained.size)
            assertTrue(rollbackOutcome.dormant.isEmpty())
            assertEquals(MountBindingState.ACTIVE, store.currentBinding(instanceA.id, id, "output")!!.state)
            assertEquals("Rollback must retain the binding grant", grantBefore, store.currentBinding(instanceA.id, id, "output")!!.grant)

            registry.reconcile(snapshot)
            advanceUntilIdle()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(instanceA.id)?.preparation)
            assertEquals("Instance must keep the external implementation identity", id, instanceA.implementationId)
            assertFalse(
                "Instance must remain bound to the external provider, not built-in",
                instanceA.implementationId.value.startsWith("builtin:"),
            )

            repo.requestClose()
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    // ── 10.4 incompatible declaration ─────────────────────────────────────
    @Test
    fun `incompatible declaration update makes the binding typed unavailable without retarget`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = JournalKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val store = newStore(root)
            val outputDeclaration = availableDescriptor(providers, id).resourceDeclarations.mounts.single()
            val registry = runtimeRegistry(providers, JournalCapabilityHost())

            val instanceA = definition("journal-a", id, "VOICE")
            val snapshot = ChannelCatalogueSnapshot(listOf(instanceA), instanceA.id)
            registry.reconcile(snapshot)
            advanceUntilIdle()
            val reconciled = mutableListOf<String>()
            val controller = newController(store, reconciled)
            bindOutput(controller, instanceA.id, id, outputDeclaration, URI_A)
            registry.reconcileResourceBinding(snapshot, instanceA.id)
            advanceUntilIdle()

            val grantBefore = store.currentBinding(instanceA.id, id, "output")!!.grant

            // Incompatible carrier: the mount declaration ID changed under the same identity.
            val repo = repository(root, bridge, providers)
            assertEquals(
                MutationResult.Updated(id),
                success(repo.installOrUpdate(ByteArrayInputStream(incompatibleFixture()), incompatibleSourceRecord())),
            )
            assertTrue(providers.resolve(id) is ChannelProviderResolution.Available)
            assertEquals("Provider identity must never be rewritten", "github-repository:$REPOSITORY_ID", id.value)
            val incompatibleDeclarations = availableDescriptor(providers, id).resourceDeclarations
            assertEquals(listOf("backup"), incompatibleDeclarations.mounts.map { it.id })

            // The existing "output" binding classifies as an incompatible removed declaration.
            val binding = store.currentBinding(instanceA.id, id, "output")!!
            assertEquals(
                MountBindingCompatibility.Incompatible.DeclarationRemoved,
                binding.compatibilityWith(id, incompatibleDeclarations.mounts.firstOrNull { it.id == binding.declarationId }),
            )

            // Applying the classification makes the binding dormant; the grant is preserved, not retargeted.
            val dormantOutcome = MountBindingUpdateRules.classify(
                id,
                incompatibleDeclarations,
                store.bindings().filter { it.implementationId == id },
            )
            assertTrue(dormantOutcome.retained.isEmpty())
            assertEquals(1, dormantOutcome.dormant.size)
            val applied = store.applyUpdate(dormantOutcome) as? MountBindingUpdateResult.Committed
                ?: throw AssertionError("applyUpdate must commit dormancy")
            val dormant = applied.snapshot.single()
            assertEquals(MountBindingState.DORMANT, dormant.state)
            assertEquals("output", dormant.declarationId)
            assertEquals(id, dormant.implementationId)
            assertEquals("Dormancy must preserve the grant, never retarget it", grantBefore, dormant.grant)

            // No silent retarget: the renamed mount has no binding.
            assertNull(store.currentBinding(instanceA.id, id, "backup"))

            // The dormant binding projects a typed unavailability, not availability.
            val dormantAvailability = MountAvailabilityProjection.project(id, outputDeclaration, dormant)
            assertTrue(
                "Dormant binding must project typed unavailability",
                dormantAvailability is MountAvailability.Unavailable &&
                    dormantAvailability.reason == MountUnavailableReason.Dormant,
            )

            // The editor now shows the renamed required mount as unbound/blocking.
            val entries = editorEntries(providers, store, instanceA.id, id)
            assertEquals(listOf("backup"), entries.map { it.declaration.declarationId })
            assertTrue(entries.single().availability is MountAvailability.Unavailable)
            assertNotNull(MountEditorProjection.requiredResourceUnavailability(entries))

            // Rollback restores the exact declaration and reactivates the same binding/grant.
            assertEquals(MutationResult.RolledBack(id), success(repo.rollback(sourceRecord().repositoryId)))
            val restoredDeclarations = availableDescriptor(providers, id).resourceDeclarations
            assertEquals(listOf("output"), restoredDeclarations.mounts.map { it.id })
            val rollbackOutcome = MountBindingUpdateRules.classify(
                id,
                restoredDeclarations,
                store.bindings().filter { it.implementationId == id },
            )
            assertEquals(1, rollbackOutcome.retained.size)
            store.applyUpdate(rollbackOutcome)
            val reactivated = store.currentBinding(instanceA.id, id, "output")!!
            assertEquals(MountBindingState.ACTIVE, reactivated.state)
            assertEquals("Rollback must restore the original grant", grantBefore, reactivated.grant)
            assertTrue(
                MountAvailabilityProjection.project(id, outputDeclaration, reactivated) is MountAvailability.Available,
            )

            repo.requestClose()
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    // ── 2.9 restart ───────────────────────────────────────────────────────
    @Test
    fun `restart restores two distinct bindings and live instances independently`() = runTest {
        withTemporaryDirectory { root ->
            // ── First run: create two instances with distinct mount selections. ──
            val firstBridge = JournalKernelBridge()
            val firstProviders = ChannelImplementationProviderRegistry()
            val id = install(root, firstBridge, firstProviders)
            val firstStore = newStore(root)
            val outputDeclaration = availableDescriptor(firstProviders, id).resourceDeclarations.mounts.single()
            val firstRegistry = runtimeRegistry(firstProviders, JournalCapabilityHost())

            val instanceA = definition("journal-a", id, "VOICE")
            val instanceB = definition("journal-b", id, "TRANSCRIPT")
            val snapshot = ChannelCatalogueSnapshot(listOf(instanceA, instanceB), instanceA.id)
            firstRegistry.reconcile(snapshot)
            advanceUntilIdle()
            val reconciled = mutableListOf<String>()
            val firstController = newController(firstStore, reconciled)
            bindOutput(firstController, instanceA.id, id, outputDeclaration, URI_A)
            bindOutput(firstController, instanceB.id, id, outputDeclaration, URI_B)
            firstRegistry.reconcileResourceBinding(snapshot, instanceA.id)
            advanceUntilIdle()
            firstRegistry.reconcileResourceBinding(snapshot, instanceB.id)
            advanceUntilIdle()
            assertEquals(ChannelPreparationAvailability.Available, firstRegistry.getRuntimeSnapshot(instanceA.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, firstRegistry.getRuntimeSnapshot(instanceB.id)?.preparation)
            assertEquals(2, firstStore.bindings().size)
            val grantAPhase1 = firstStore.currentBinding(instanceA.id, id, "output")!!.grant
            val grantBPhase1 = firstStore.currentBinding(instanceB.id, id, "output")!!.grant
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(firstRegistry))

            // ── Restart: fresh store over the same file restores both bindings. ──
            val restartedStore = MountBindingStore(storeFile(root))
            val loaded = restartedStore.load()
            assertTrue("Restarted store must load", loaded is io.talkcan.resource.MountBindingLoadResult.Loaded)
            assertEquals(2, restartedStore.bindings().size)

            val restoredA = restartedStore.bindingsForInstance(instanceA.id).single()
            val restoredB = restartedStore.bindingsForInstance(instanceB.id).single()
            assertEquals("output", restoredA.declarationId)
            assertEquals("output", restoredB.declarationId)
            assertEquals(id, restoredA.implementationId)
            assertEquals(id, restoredB.implementationId)
            assertEquals(MountBindingState.ACTIVE, restoredA.state)
            assertEquals(MountBindingState.ACTIVE, restoredB.state)
            assertEquals(URI_A, SafGrantCodec.decode(restoredA.grant)!!.treeUri)
            assertEquals(URI_B, SafGrantCodec.decode(restoredB.grant)!!.treeUri)
            assertNotEquals("Restored bindings must remain distinct", restoredA.grant, restoredB.grant)
            assertEquals("Restored A grant must be byte-identical to the phase-1 selection", grantAPhase1, restoredA.grant)
            assertEquals("Restored B grant must be byte-identical to the phase-1 selection", grantBPhase1, restoredB.grant)

            // ── Restart: fresh service composition republishes and re-reconciles both. ──
            val restartBridge = JournalKernelBridge()
            val restartProviders = ChannelImplementationProviderRegistry()
            val restartedRepo = repository(root, restartBridge, restartProviders)
            assertEquals(Unit, success(restartedRepo.loadAndPublish()))
            assertTrue(restartProviders.resolve(id) is ChannelProviderResolution.Available)
            assertEquals("github-repository:$REPOSITORY_ID", id.value)

            val restartedRegistry = runtimeRegistry(restartProviders, JournalCapabilityHost())
            restartedRegistry.reconcile(snapshot)
            advanceUntilIdle()

            assertEquals("Restart must construct exactly two fresh states", 2, restartBridge.createdStates.size)
            assertNotEquals(restartBridge.createdStates[0], restartBridge.createdStates[1])
            assertTrue("Restart must inherit no closed states", restartBridge.closedStates.isEmpty())
            assertEquals(ChannelPreparationAvailability.Available, restartedRegistry.getRuntimeSnapshot(instanceA.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, restartedRegistry.getRuntimeSnapshot(instanceB.id)?.preparation)
            assertTrue("Restart must restore VOICE scalar", restartBridge.startupModes.contains("VOICE"))
            assertTrue("Restart must restore TRANSCRIPT scalar", restartBridge.startupModes.contains("TRANSCRIPT"))
            // No automatic grants: phase 2 performs no selection; the bindings are the byte-identical persisted ones asserted above.

            restartedRepo.requestClose()
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(restartedRegistry))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun storeFile(root: File): File = File(root, "mount-bindings.json")

    private fun newStore(root: File): MountBindingStore = MountBindingStore(storeFile(root)).also { store ->
        assertTrue(
            "Binding store must load",
            store.load() is io.talkcan.resource.MountBindingLoadResult.Loaded,
        )
    }

    private fun newController(store: MountBindingStore, reconciled: MutableList<String>): MountSelectionController =
        MountSelectionController(SafMountAdapter(store, FakeSafGrantController())) { request, _ ->
            reconciled += request.ownerInstanceId
        }

    private fun bindOutput(
        controller: MountSelectionController,
        instanceId: String,
        implementationId: ChannelImplementationId,
        declaration: PackageMountDeclaration,
        treeUri: String,
    ): MountSelectionResult {
        controller.begin(MountSelectionRequest(instanceId, implementationId, declaration.id), declaration)
        val result = controller.complete(SafTreePickerOutcome.Selected(treeUri))
        assertTrue("Selection must bind: $result", result is MountSelectionResult.Bound)
        return result
    }

    private fun availableDescriptor(
        providers: ChannelImplementationProviderRegistry,
        implementationId: ChannelImplementationId,
    ) = (providers.resolve(implementationId) as ChannelProviderResolution.Available).provider.descriptor

    private fun editorEntries(
        providers: ChannelImplementationProviderRegistry,
        store: MountBindingStore,
        instanceId: String,
        implementationId: ChannelImplementationId,
    ): List<MountEditorEntry> {
        val declarations = availableDescriptor(providers, implementationId).resourceDeclarations.mounts
        return MountEditorProjection.entries(declarations) { declarationId ->
            val declaration = declarations.firstOrNull { it.id == declarationId }
                ?: return@entries MountAvailability.Unavailable(MountUnavailableReason.Undeclared)
            val binding = store.currentBinding(instanceId, implementationId, declarationId)
            MountAvailabilityProjection.project(implementationId, declaration, binding)
        }
    }

    private fun assertBlockingOutputMount(
        providers: ChannelImplementationProviderRegistry,
        store: MountBindingStore,
        instanceId: String,
        implementationId: ChannelImplementationId,
    ) {
        val unavailability = MountEditorProjection.requiredResourceUnavailability(
            editorEntries(providers, store, instanceId, implementationId),
        )
        assertNotNull("Unbound required mount must block the instance", unavailability)
        assertEquals(listOf("output"), unavailability!!.blockingDeclarationIds)
    }

    private fun assertClearOutputMount(
        providers: ChannelImplementationProviderRegistry,
        store: MountBindingStore,
        instanceId: String,
        implementationId: ChannelImplementationId,
    ) {
        assertNull(
            "Bound required mount must clear the instance block",
            MountEditorProjection.requiredResourceUnavailability(
                editorEntries(providers, store, instanceId, implementationId),
            ),
        )
    }

    private fun repository(
        root: File,
        bridge: LuaKernelBridge,
        providers: ChannelImplementationProviderRegistry,
    ): InstalledPackageRepository = InstalledPackageRepository(
        store = InstalledPackageStore(root),
        bridge = bridge,
        publisher = { materialized ->
            when (providers.publishInstalledProviders(
                materialized.bindings,
                materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) },
            )) {
                is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                    PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
                )
            }
        },
        dispatcher = Dispatchers.Unconfined,
    )

    private suspend fun install(
        root: File,
        bridge: LuaKernelBridge,
        providers: ChannelImplementationProviderRegistry,
    ): ChannelImplementationId {
        val repo = repository(root, bridge, providers)
        val result = repo.installOrUpdate(ByteArrayInputStream(fixture()), sourceRecord())
        assertTrue("Install must succeed: $result", result is PackageOutcome.Success)
        return InstalledProviderId.derive(sourceRecord().repositoryId)
    }

    private fun runtimeRegistry(
        providers: ChannelImplementationProviderRegistry,
        host: ChannelCapabilityHost,
    ): ChannelRuntimeRegistry = ChannelRuntimeRegistry(
        providers = providers,
        capabilityHost = host,
        invocationBoundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.fromDispatcher(Dispatchers.Unconfined),
            RuntimeInvocationPolicy(16, 1_000, 1_000, 1_000),
        ),
        runtimeScope = CoroutineScope(Dispatchers.Unconfined),
        closeScope = CoroutineScope(Dispatchers.Unconfined),
        shutdownAwaitMillis = 2_000,
    )

    private suspend fun shutdown(registry: ChannelRuntimeRegistry): ChannelRuntimeRegistryShutdownResult =
        registry.shutdownAndAwait()

    private fun definition(id: String, implementationId: ChannelImplementationId, outputMode: String) =
        ChannelDefinition(
            id = id,
            name = "Journal $outputMode",
            implementationId = implementationId,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("output_mode", outputMode)),
        )

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-journal-lifecycle-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
    private fun fixture(): ByteArray {
        val artifact = requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH))
            .use { it.readBytes() }
        assertEquals(ARTIFACT_SIZE, artifact.size)
        assertEquals(ARTIFACT_SHA256, sha256(artifact))
        return artifact
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Test-only carrier derived from the exact fixture: identical mounts and
     * identity, only `packageVersion` advanced — a schema-compatible successor
     * for update/rollback traversal. Not a published release.
     */
    private fun successorFixture(): ByteArray = repackaged(fixture()) { manifest ->
        manifest.replace("\"packageVersion\":\"$PACKAGE_VERSION\"", "\"packageVersion\":\"1.0.2\"")
    }

    /**
     * Test-only carrier derived from the exact fixture: the required mount's
     * declaration ID is changed under the same identity — an incompatible
     * resource declaration for compatibility-rule traversal. Not a published release.
     */
    private fun incompatibleFixture(): ByteArray = repackaged(fixture()) { manifest ->
        manifest.replace("\"id\":\"output\",\"kind\"", "\"id\":\"backup\",\"kind\"")
    }

    /**
     * Patches the STORED manifest in place while preserving the fixture's exact
     * Unix ZIP metadata. Test carrier transforms are deliberately length-stable.
     */
    private fun repackaged(artifact: ByteArray, transformManifest: (String) -> String): ByteArray {
        val originalManifest = ZipInputStream(ByteArrayInputStream(artifact)).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json") {
                    require(entry.method == ZipEntry.STORED) { "Manifest fixture must be STORED" }
                    found = zip.readBytes()
                    break
                }
            }
            requireNotNull(found) { "Fixture has no manifest.json" }
        }
        val transformed = transformManifest(String(originalManifest, Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
        require(!transformed.contentEquals(originalManifest)) {
            "Carrier transform did not modify the manifest"
        }
        require(transformed.size == originalManifest.size) {
            "Carrier transform must preserve manifest byte length"
        }

        val rewritten = artifact.copyOf()
        val local = findZipHeader(rewritten, 0x04034b50L, 30, 26, "manifest.json")
        val localNameLength = readZipU16(rewritten, local + 26)
        val localExtraLength = readZipU16(rewritten, local + 28)
        val payloadOffset = local + 30 + localNameLength + localExtraLength
        require(
            rewritten.copyOfRange(payloadOffset, payloadOffset + originalManifest.size)
                .contentEquals(originalManifest),
        ) { "Local manifest payload does not match extracted bytes" }
        transformed.copyInto(rewritten, payloadOffset)

        val crc = CRC32().apply { update(transformed) }.value
        writeZipU32(rewritten, local + 14, crc)
        val central = findZipHeader(rewritten, 0x02014b50L, 46, 28, "manifest.json")
        writeZipU32(rewritten, central + 16, crc)
        return rewritten
    }

    private fun findZipHeader(
        archive: ByteArray,
        signature: Long,
        fixedSize: Int,
        nameLengthOffset: Int,
        name: String,
    ): Int {
        val encodedName = name.toByteArray(Charsets.UTF_8)
        for (offset in 0..archive.size - fixedSize - encodedName.size) {
            if (readZipU32(archive, offset) != signature) continue
            if (readZipU16(archive, offset + nameLengthOffset) != encodedName.size) continue
            val nameOffset = offset + fixedSize
            if (encodedName.indices.all { archive[nameOffset + it] == encodedName[it] }) {
                return offset
            }
        }
        error("ZIP header not found for $name")
    }

    private fun readZipU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readZipU32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }

    private fun writeZipU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun sourceRecord() = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan", "journal-channel"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v$PACKAGE_VERSION", false),
        asset = GitHubAssetIdentity(ASSET_ID, "talkcan-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    private fun successorSourceRecord() = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan", "journal-channel"),
        release = GitHubReleaseIdentity("2", "v1.0.1", false),
        asset = GitHubAssetIdentity("2", "talkcan-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    private fun incompatibleSourceRecord() = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan", "journal-channel"),
        release = GitHubReleaseIdentity("3", "v2.0.0", false),
        asset = GitHubAssetIdentity("3", "talkcan-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    private fun assertArrayEqualsMsg(message: String, expected: ByteArray, actual: ByteArray) {
        assertTrue(message, expected.contentEquals(actual))
    }

    /**
     * JVM recording bridge for the external Journal package, confined to the
     * native kernel boundary. Tracks created/closed states and the startup
     * `output_mode` scalar; readiness reports ready when the single mapped
     * capability (`audio.transcription`) is available.
     */
    private class JournalKernelBridge : LuaKernelBridge {
        private val nextState = AtomicLong(1)
        val createdStates = mutableListOf<Long>()
        val closedStates = mutableListOf<Long>()
        val startupModes = mutableListOf<String>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextState.getAndIncrement()
            createdStates += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "journal-fixture")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
            complete(handle)

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = complete(handle)

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = complete(operation.stateHandle, "{\"ok\":true}")

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = complete(operation.stateHandle)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = complete(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome =
            LuaKernelOutcome.Snapshot(
                handle.stateId.value,
                handle.generation.value,
                null,
                LUA_VERSION,
                API_VERSION,
                "journal-fixture",
            )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            closedStates += handle.stateId.value
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun setResourceContext(
            handle: LuaStateHandle,
            resourceContextJson: String,
        ): LuaKernelOutcome = complete(handle)

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = complete(handle, "[\"startup\",\"handle_readiness\",\"handle_input\"]")

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val mode = (((config as? LuaValue.Map)?.pairs?.get("values") as? LuaValue.Map)
                ?.pairs?.get("output_mode") as? LuaValue.StringValue)?.value ?: "VOICE_AND_TRANSCRIPT"
            startupModes += mode
            return complete(handle)
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val capabilities = (arguments as? LuaValue.Map)?.pairs?.get("capabilities") as? LuaValue.Map
            val transcription = (capabilities?.pairs?.get("audio.transcription") as? LuaValue.StringValue)?.value
            val ready = transcription == "available"
            return complete(handle, "{\"ready\":$ready}")
        }

        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = complete(handle, "{\"ok\":true}")

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = complete(handle)

        private fun complete(handle: LuaStateHandle, value: String? = null) =
            LuaKernelOutcome.Completed(
                handle.stateId.value,
                handle.generation.value,
                null,
                value,
                null,
                LUA_VERSION,
                API_VERSION,
                "journal-fixture",
            )
    }

    /**
     * Capability host for the external Journal package. Provides the single
     * capability the JVM readiness path maps (`audio.transcription`); storage
     * and audio-file authorities are mount-backed and not acquired in these
     * lifecycle tests.
     */
    private class JournalCapabilityHost : ChannelCapabilityHost {
        override suspend fun availability(identity: CapabilityScopeIdentity, key: CapabilityKey<*>) =
            if (key == CapabilityKey.Transcription) {
                CapabilityAvailability.Available
            } else {
                CapabilityAvailability.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            }

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> =
            if (key != CapabilityKey.Transcription) {
                HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            } else {
                HostedCapabilityAcquisition.Available(port(key), {})
            }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)

        @Suppress("UNCHECKED_CAST")
        private fun <T : ChannelCapabilityPort> port(key: CapabilityKey<T>): T = when (key) {
            CapabilityKey.Transcription -> object : TranscriptionCapability {
                override suspend fun transcribe(recording: OpaqueAudioRecording) =
                    CapabilityOperationResult.Success(Transcription("transcript"))
            } as T
            else -> error("Unknown capability: $key")
        }
    }

    /** Platform-free SAF grant controller mirroring the focused adapter tests. */
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

    private companion object {
        const val RESOURCE_PATH = "journal-channel/talkcan-channel.zip"
        const val REPOSITORY_ID = "1309332087"
        const val RELEASE_ID = "360293138"
        const val ASSET_ID = "491214391"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val PACKAGE_VERSION = "2.0.0"
        const val ARTIFACT_SIZE = 69740
        const val ARTIFACT_SHA256 = "a1ac4217d2ca044eefed19da02899f3f3143f10ac22d7c8744bc1678e952b110"
        const val URI_A = "content://com.android.externalstorage.documents/tree/primary%3AJournalA"
        const val URI_B = "content://com.android.externalstorage.documents/tree/primary%3AJournalB"
        const val URI_C = "content://com.android.externalstorage.documents/tree/primary%3AJournalC"
    }
}
