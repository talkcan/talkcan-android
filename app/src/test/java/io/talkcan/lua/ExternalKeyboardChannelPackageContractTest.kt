package io.talkcan.lua

import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.PackageOutcome
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.PackageValidator
import io.talkcan.dependency.ValidatedPackageRevision
import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the exact external Keyboard fixture through the embedded Lua kernel. */
class ExternalKeyboardChannelPackageContractTest {

    @Test
    fun `package readiness uses profile reference and keyboard availability only`() {
        withPackageState { bridge, handle ->
            val available = readiness(bridge, handle, "available", "available", "unavailable")
            assertTrue(available.getBoolean("ready"))
            assertFalse(available.has("prepare"))

            val recoverable = readiness(bridge, handle, "available", "recoverable", "unavailable")
            assertFalse(recoverable.getBoolean("ready"))
            assertEquals("keyboard.output", recoverable.getJSONArray("prepare").getString(0))
            assertEquals(1, recoverable.getJSONArray("prepare").length())

            val missingReference = readiness(bridge, handle, "unavailable", "recoverable", "available")
            assertFalse(missingReference.getBoolean("ready"))
            assertFalse(missingReference.has("prepare"))
        }
    }

    @Test
    fun `package capture transcribes and sends one trailing space through configured profile`() {
        withPackageState { bridge, handle ->
            val transcription = capture(bridge, handle)
            assertEquals(HostOperationKind.TRANSCRIBE, transcription.claim.kind)
            assertEquals("recording-token", transcription.claim.audioToken)

            val keyboard = resumeAndClaim(bridge, transcription.yielded, true, "hello")
            assertEquals(HostOperationKind.KEYBOARD_SEND_TEXT, keyboard.claim.kind)
            assertEquals("hello ", keyboard.claim.text)
            assertEquals("linux:us", keyboard.claim.profile)
            assertFalse(keyboard.yielded.value.orEmpty().contains("hello"))
            assertFalse(keyboard.yielded.value.orEmpty().contains("linux:us"))

            val completed = resume(bridge, keyboard.yielded, true, """{"status":"delivered"}""")
            assertEquals("""{"ok":true}""", JSONObject(completed.value!!).toString())
            assertTrue(bridge.claimHostOperation(handle, keyboard.requestId) is HostOperationClaim.Rejected)
        }
    }

    @Test
    fun `package preserves trailing space and non-delivered output fails without replay or content leak`() {
        withPackageState(profile = "private:layout") { bridge, handle ->
            val transcription = capture(bridge, handle)
            val keyboard = resumeAndClaim(
                bridge,
                transcription.yielded,
                true,
                "private transcript ",
            )
            assertEquals("private transcript ", keyboard.claim.text)
            assertEquals("private:layout", keyboard.claim.profile)

            val completed = resume(
                bridge,
                keyboard.yielded,
                true,
                """{"status":"indeterminate","reason":"disconnected"}""",
            )
            val value = completed.value!!
            assertEquals("E_OUTPUT_INDETERMINATE", JSONObject(value).getJSONObject("error").getString("code"))
            assertFalse(value.contains("private transcript"))
            assertFalse(value.contains("private:layout"))
            assertTrue(bridge.claimHostOperation(handle, keyboard.requestId) is HostOperationClaim.Rejected)
        }
    }

    @Test
    fun `package SOS sends exactly one semantic Enter through configured profile`() {
        withPackageState(profile = "windows:us") { bridge, handle ->
            val yielded = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Map(mapOf("event" to LuaValue.StringValue("sos"))),
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded SOS request")
            val requestId = yielded.value?.toLongOrNull() ?: error("missing opaque SOS request id")
            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted SOS claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
            assertEquals("enter", claim.key)
            assertEquals("windows:us", claim.profile)
            assertFalse(yielded.value.orEmpty().contains("enter"))
            assertFalse(yielded.value.orEmpty().contains("windows:us"))

            val completed = resume(bridge, yielded, true, """{"status":"delivered"}""")
            assertEquals("""{"ok":true}""", JSONObject(completed.value!!).toString())
            assertTrue(bridge.claimHostOperation(handle, requestId) is HostOperationClaim.Rejected)
        }
    }

    private fun readiness(
        bridge: LuaKernelBridge,
        handle: LuaStateHandle,
        reference: String,
        keyboard: String,
        transcription: String,
    ): JSONObject {
        val outcome = bridge.invokeCallback(
            handle,
            LuaCallbackHandle(handle, "handle_readiness"),
            LuaValue.Map(
                mapOf(
                    "references" to LuaValue.Map(
                        mapOf(
                            "host_os" to LuaValue.StringValue("available"),
                            "host_layout" to LuaValue.StringValue("available"),
                            "host_profile" to LuaValue.StringValue(reference),
                        ),
                    ),
                    "capabilities" to LuaValue.Map(
                        mapOf(
                            "keyboard.output" to LuaValue.StringValue(keyboard),
                            "audio.transcription" to LuaValue.StringValue(transcription),
                        ),
                    ),
                    "resources" to LuaValue.Map(mapOf("mounts" to LuaValue.Map(emptyMap()))),
                ),
            ),
        ) as? LuaKernelOutcome.Completed ?: error("expected completed readiness callback")
        return JSONObject(outcome.value!!)
    }

    private data class ClaimedYield(
        val yielded: LuaKernelOutcome.Yielded,
        val requestId: Long,
        val claim: HostOperationClaim.Admitted,
    )

    private fun capture(bridge: LuaKernelBridge, handle: LuaStateHandle): ClaimedYield {
        val yielded = bridge.invokeInputCallback(
            handle,
            LuaCallbackHandle(handle, "handle_input"),
            LuaValue.Map(
                mapOf(
                    "event" to LuaValue.StringValue("capture"),
                    "session" to LuaValue.StringValue("session-1"),
                    "timestamp" to LuaValue.StringValue("2026-07-23T00:00:00Z"),
                    "metadata" to LuaValue.Map(emptyMap()),
                ),
            ),
            "recording-token",
        ) as? LuaKernelOutcome.Yielded ?: error("expected yielded transcription request")
        return claim(bridge, handle, yielded)
    }

    private fun resumeAndClaim(
        bridge: LuaKernelBridge,
        yielded: LuaKernelOutcome.Yielded,
        success: Boolean,
        value: String,
    ): ClaimedYield {
        val next = bridge.resume(operation(yielded), success, value) as? LuaKernelOutcome.Yielded
            ?: error("expected successor yielded request")
        return claim(bridge, yielded.stateHandle(), next)
    }

    private fun claim(
        bridge: LuaKernelBridge,
        handle: LuaStateHandle,
        yielded: LuaKernelOutcome.Yielded,
    ): ClaimedYield {
        val requestId = yielded.value?.toLongOrNull() ?: error("missing opaque request id")
        val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
            ?: error("expected admitted request")
        return ClaimedYield(yielded, requestId, claim)
    }

    private fun resume(
        bridge: LuaKernelBridge,
        yielded: LuaKernelOutcome.Yielded,
        success: Boolean,
        value: String,
    ): LuaKernelOutcome.Completed = bridge.resume(operation(yielded), success, value)
        as? LuaKernelOutcome.Completed ?: error("expected completed callback")

    private fun operation(yielded: LuaKernelOutcome.Yielded) = LuaOperationHandle(
        stateHandle = yielded.stateHandle(),
        coroutineId = LuaCoroutineId(yielded.coroutineId),
        operationId = LuaOperationId(yielded.operationId),
    )

    private fun LuaKernelOutcome.Yielded.stateHandle() = LuaStateHandle(
        stateId = LuaStateId(stateId),
        generation = LuaStateGeneration(generation),
    )

    private fun withPackageState(
        profile: String = "linux:us",
        block: (LuaKernelBridge, LuaStateHandle) -> Unit,
    ) {
        val revision = fixtureRevision()
        val bridge: LuaKernelBridge = KotlinLuaKernelBridge()
        val createdOutcome = bridge.create(
            LuaKernelConfig(hookInterval = 100, instructionBudget = 10_000_000),
        )
        assertTrue(
            "the Kotlin kernel must create the package state: $createdOutcome",
            createdOutcome is LuaKernelOutcome.Created,
        )
        val created = createdOutcome as LuaKernelOutcome.Created
        val handle = LuaStateHandle(LuaStateId(created.stateId), LuaStateGeneration(created.generation))
        try {
            val context = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $context", context is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(handle, revision.programImage.entryPoint, revision.sourceMap)
            assertTrue("exact fixture program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val startup = bridge.invokeStartupCallback(
                handle,
                LuaCallbackHandle(handle, "startup"),
                LuaValue.Map(
                    mapOf(
                        "schema_version" to LuaValue.Real(1.0),
                        "values" to LuaValue.Map(
                            mapOf(
                                "host_os" to LuaValue.StringValue("fixture-os"),
                                "host_layout" to LuaValue.StringValue("fixture-layout"),
                                "host_profile" to LuaValue.StringValue(profile),
                            ),
                        ),
                    ),
                ),
            )
            assertTrue("fixture startup must complete: $startup", startup is LuaKernelOutcome.Completed)
            block(bridge, handle)
        } finally {
            bridge.close(handle)
        }
    }

    private fun fixtureRevision(): ValidatedPackageRevision {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)).use { it.readBytes() }
        val root = createTempDirectory("external-keyboard-package-").toFile()
        return try {
            when (val outcome = PackageValidator.validatePackage(
                ByteArrayInputStream(bytes),
                sourceRecord(),
                File(root, "staging.zip"),
            )) {
                is PackageOutcome.Success -> outcome.value
                is PackageOutcome.Failure -> throw AssertionError("fixture validation failed: ${outcome.error}")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sourceRecord() = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity("1310281239"),
        coordinates = GitHubRepositoryCoordinates("talkcan", "keyboard-channel"),
        release = GitHubReleaseIdentity("1310281239001", "v1.0.0", false),
        asset = GitHubAssetIdentity("1310281239002", "talkcan-channel.zip"),
        ownerId = "1224006",
    )

    private companion object {
        const val RESOURCE_PATH = "keyboard-channel/talkcan-channel.zip"
    }
}
