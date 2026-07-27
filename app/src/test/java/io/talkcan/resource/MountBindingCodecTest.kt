package io.talkcan.resource

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountKind
import io.talkcan.model.ChannelImplementationId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MountBindingCodecTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val implementation = ChannelImplementationId("github-repository:123456")

    private fun binding(
        instance: String = "instance-a",
        declarationId: String = "output",
        grant: String = "grant-a",
        status: MountBindingStatus = MountBindingStatus.AVAILABLE,
        state: MountBindingState = MountBindingState.ACTIVE,
    ): MountBinding = MountBinding(
        channelInstanceId = instance,
        implementationId = implementation,
        declarationId = declarationId,
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        grant = PlatformGrantBlob(grant.toByteArray(Charsets.UTF_8)),
        status = status,
        state = state,
    )

    @Test
    fun roundTripPreservesEveryFieldAndGrantBytes() {
        val original = listOf(
            binding(instance = "instance-a", grant = "grant-a"),
            binding(instance = "instance-b", declarationId = "cache", grant = "grant-b",
                status = MountBindingStatus.NEEDS_REAUTHORIZATION, state = MountBindingState.DORMANT),
        )

        val decoded = MountBindingCodec.decode(MountBindingCodec.encode(original))

        val success = decoded as? MountBindingDecodeResult.Success
            ?: throw AssertionError("Round trip must decode: $decoded")
        assertEquals(original, success.bindings)
        for (pair in original.zip(success.bindings)) {
            assertTrue(
                "Grant bytes must survive the round trip exactly",
                pair.first.grant.toByteArray().contentEquals(pair.second.grant.toByteArray())
            )
        }
    }

    @Test
    fun encodingIsDeterministicAcrossInsertionOrder() {
        val a = binding(instance = "instance-a", grant = "grant-a")
        val b = binding(instance = "instance-b", grant = "grant-b")
        val c = binding(instance = "instance-a", declarationId = "cache", grant = "grant-c")

        assertEquals(
            MountBindingCodec.encode(listOf(a, b, c)),
            MountBindingCodec.encode(listOf(c, b, a)),
        )
    }

    @Test
    fun emptyBindingListRoundTrips() {
        val decoded = MountBindingCodec.decode(MountBindingCodec.encode(emptyList()))
        val success = decoded as? MountBindingDecodeResult.Success
            ?: throw AssertionError("Empty document must decode: $decoded")
        assertTrue(success.bindings.isEmpty())
    }

    @Test
    fun encodedDocumentUsesOnlyPortableVocabulary() {
        val encoded = MountBindingCodec.encode(listOf(binding()))

        val allowedTokens = listOf(
            "version", "bindings",
            "channelInstanceId", "implementationId", "declarationId",
            "kind", "access", "status", "state", "grant",
            "directory-tree", "read-write", "available", "active",
            "instance-a", "output", implementation.value,
        )
        for (token in allowedTokens) {
            assertTrue("Document must contain $token", encoded.contains(token))
        }
        assertFalse("Document must not expose content URIs", encoded.contains("content:"))
        assertFalse("Document must not expose Android classes", encoded.contains("android"))
        assertFalse("Document must not expose raw paths", encoded.contains("/storage"))
        assertFalse("Document must not expose file schemes", encoded.contains("file:"))
    }

    @Test
    fun decodeRejectsUnsupportedVersion() {
        val encoded = MountBindingCodec.encode(listOf(binding()))
            .replace("\"version\": ${MountBindingCodec.CURRENT_DOCUMENT_VERSION}", "\"version\": 2")

        val decoded = MountBindingCodec.decode(encoded)
        val failure = decoded as? MountBindingDecodeResult.Failure
            ?: throw AssertionError("Unsupported version must fail: $decoded")
        assertTrue(
            "Expected UnsupportedDocumentVersion, got ${failure.error}",
            failure.error is MountBindingDecodeError.UnsupportedDocumentVersion
        )
    }

    @Test
    fun decodeRejectsMalformedShapes() {
        val valid = MountBindingCodec.encode(listOf(binding()))
        val grantBase64 = Base64.getEncoder().encodeToString("grant-a".toByteArray(Charsets.UTF_8))
        val bindingObject = """
            {
              "channelInstanceId": "instance-a",
              "implementationId": "github-repository:123456",
              "declarationId": "output",
              "kind": "directory-tree",
              "access": "read-write",
              "status": "available",
              "state": "active",
              "grant": "$grantBase64"
            }
        """.trimIndent()

        val cases = mapOf(
            "empty document" to "",
            "root array" to "[]",
            "unknown root key" to valid.replace("\"bindings\"", "\"bindings2\""),
            "missing root key" to "{\"version\": 1}",
            "string version" to valid.replace("\"version\": 1", "\"version\": \"1\""),
            "fractional version" to valid.replace("\"version\": 1", "\"version\": 1.5"),
            "bindings not array" to valid.replace("[\n", "{\n").replace("\n  ]", "\n  }"),
            "unknown binding key" to bindingDocument(bindingObject.replace(
                "\"grant\":",
                "\"grant2\": \"x\",\n      \"grant\":",
            )),
            "missing binding key" to bindingDocument(
                bindingObject.lines().filterNot { it.contains("\"state\"") }.joinToString("\n")
            ),
            "invalid kind" to bindingDocument(bindingObject.replace("directory-tree", "block-device")),
            "invalid access" to bindingDocument(bindingObject.replace("read-write", "write-only")),
            "invalid status" to bindingDocument(bindingObject.replace("available", "mounted")),
            "invalid state" to bindingDocument(bindingObject.replace("active", "paused")),
            "non-string grant" to bindingDocument(bindingObject.replace("\"$grantBase64\"", "7")),
            "invalid base64 grant" to bindingDocument(bindingObject.replace(grantBase64, "!!!not-base64!!!")),
            "empty grant" to bindingDocument(bindingObject.replace(grantBase64, "")),
            "trailing garbage" to "$valid trailing",
            "duplicate binding record" to """
                {
                  "version": 1,
                  "bindings": [
                $bindingObject,
                $bindingObject
                  ]
                }
            """.trimIndent(),
            "duplicate json key" to bindingDocument(
                bindingObject.replace(
                    "\"declarationId\": \"output\",",
                    "\"declarationId\": \"other\",\n      \"declarationId\": \"output\",",
                )
            ),
        )

        for ((name, document) in cases) {
            val decoded = MountBindingCodec.decode(document)
            assertTrue("Case '$name' must fail, got $decoded", decoded is MountBindingDecodeResult.Failure)
        }
    }

    @Test
    fun decodeRejectsOverBoundGrant() {
        val huge = Base64.getEncoder().encodeToString(
            ByteArray(MountBindingLimits.MAX_GRANT_BLOB_BYTES + 1) { 0x41 }
        )
        val document = """
            {
              "version": 1,
              "bindings": [
                {
                  "channelInstanceId": "instance-a",
                  "implementationId": "github-repository:123456",
                  "declarationId": "output",
                  "kind": "directory-tree",
                  "access": "read-write",
                  "status": "available",
                  "state": "active",
                  "grant": "$huge"
                }
              ]
            }
        """.trimIndent()

        val decoded = MountBindingCodec.decode(document)
        val failure = decoded as? MountBindingDecodeResult.Failure
            ?: throw AssertionError("Over-bound grant must fail: $decoded")
        assertTrue(
            "Expected InvalidBinding, got ${failure.error}",
            failure.error is MountBindingDecodeError.InvalidBinding
        )
    }

    private fun bindingDocument(bindingObject: String): String = """
        {
          "version": 1,
          "bindings": [
        $bindingObject
          ]
        }
    """.trimIndent()
}
