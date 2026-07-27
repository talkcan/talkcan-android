package io.talkcan.service

import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.lua.ImmutableProgramImage
import io.talkcan.lua.LuaChannelImplementationProvider
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.lua.CompiledConfigurationProvider
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import io.talkcan.lua.LuaProgramRequirements
import io.talkcan.lua.ProgramImageCreationResult
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderRevisionFingerprint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test-only identity for generic Lua provider behavior. */
private val TEST_LUA_IMPLEMENTATION_ID = ChannelImplementationId("internal:lua")

/** Empty schema-version-1 declaration; mirrors a materialized package with no fields. */
private fun emptyConfigurationDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
    ConfigurationDataDeclaration(emptyList()),
    ConfigurationUiDeclaration(emptyList()),
)

/**
 * Constructs a test Lua provider from an image creation result (success or failure).
 * Used for testing error projection paths.
 */
private fun testLuaProviderFromResult(
    imageResult: ProgramImageCreationResult,
    bridge: LuaKernelBridge,
): LuaChannelImplementationProvider = LuaChannelImplementationProvider.fromImageResult(
    implementationId = TEST_LUA_IMPLEMENTATION_ID,
    presentation = ChannelPresentationMetadata(
        label = "Lua channel",
        summary = "LUA RUNTIME",
        unavailableMessage = "Lua program could not be constructed.",
    ),
    imageResult = imageResult,
    fingerprint = ProviderRevisionFingerprint("builtin"),
    actorFactory = { context, capabilities, kernelBridge, policy ->
        ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
    },
    bridge = bridge,
    configurationProvider = CompiledConfigurationProvider(TEST_LUA_IMPLEMENTATION_ID, emptyConfigurationDeclaration()),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeCompatibilityFailureProjectionTest {
    @Test
    fun `registry projects exact compatibility failures from real Lua provider creation`() = runTest {
        val cases = listOf(
            CompatibilityCase("luaVersion", "Lua 5.3", "Lua 5.4"),
            CompatibilityCase("apiVersion", "talkcan-lua-v2", "talkcan-lua-v1"),
        )

        cases.forEach { case ->
            val creation = ImmutableProgramImage.create(
                entryPoint = "main",
                sourceMap = mapOf("main" to "return {}"),
                requirements = LuaProgramRequirements(
                    luaVersion = if (case.requirement == "luaVersion") case.requiredVersion else "Lua 5.4",
                    apiVersion = if (case.requirement == "apiVersion") case.requiredVersion else "talkcan-lua-v1",
                ),
            )
            assertTrue("${case.requirement}: expected incompatible image creation", creation is ProgramImageCreationResult.Failure)
            val provider = testLuaProviderFromResult(
                imageResult = creation,
                bridge = KotlinLuaKernelBridge(),
            )
            val boundary = RuntimeInvocationBoundary(
                RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 4,
                    callbackTimeoutMillis = 1_000,
                    inputReleasedTimeoutMillis = 1_000,
                    closeTimeoutMillis = 1_000,
                ),
            )
            val registry = ChannelRuntimeRegistry(
                providers = ChannelImplementationProviderRegistry().also {
                    assertTrue(it.register(provider) is io.talkcan.model.ChannelProviderRegistrationResult.Registered)
                },
                capabilityHost = NoCapabilities,
                invocationBoundary = boundary,
                runtimeScope = backgroundScope,
                closeScope = backgroundScope,
                shutdownAwaitMillis = 1_000,
            )
            try {
                val definition = ChannelDefinition(
                    id = "instance-${case.requirement}",
                    name = "Compatibility ${case.requirement}",
                    implementationId = provider.descriptor.implementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = emptyPayload(),
                )

                registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
                runCurrent()

                val snapshot = requireNotNull(registry.getRuntimeSnapshot(definition.id))
                val unavailable = snapshot.preparation as? ChannelPreparationAvailability.Unavailable
                    ?: throw AssertionError("${case.requirement}: expected unavailable provider projection, got ${snapshot.preparation}")
                val reason = unavailable.reason as? ChannelPreparationReason.Provider
                    ?: throw AssertionError("${case.requirement}: expected provider reason, got ${unavailable.reason}")
                val projected = reason.error as? ChannelProviderError.RuntimeCompatibilityFailure
                    ?: throw AssertionError("${case.requirement}: expected compatibility error, got ${reason.error}")

                assertEquals(provider.descriptor.implementationId, projected.implementationId)
                assertEquals(case.requirement, projected.requirement)
                assertEquals(case.requiredVersion, projected.requiredVersion)
                assertEquals(case.supportedVersion, projected.supportedVersion)
                assertEquals(projected.message, reason.message)
            } finally {
                registry.shutdownAndAwait()
                boundary.close()
            }
        }
    }

    private data class CompatibilityCase(
        val requirement: String,
        val requiredVersion: String,
        val supportedVersion: String,
    )


    private object NoCapabilities : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = error("No capability is declared for compatibility failure coverage")

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = error("No capability is declared for compatibility failure coverage")
    }

    private companion object {
        fun emptyPayload(): OpaqueJsonObject = OpaqueJsonObject.parse("{}").getOrThrow()
    }
}
