package io.talkcan.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImmutableProgramImageTest {

    private val validRequirements = LuaProgramRequirements(
        luaVersion = LUA_VERSION,
        apiVersion = API_VERSION
    )

    @Test
    fun `defensive snapshotting of keys and source strings at image construction`() {
        val entryPoint = "main"
        val mutableSourceMap = hashMapOf(
            "main" to "local x = 1",
            "helper" to "local y = 2"
        )

        val result = ImmutableProgramImage.create(
            entryPoint = entryPoint,
            sourceMap = mutableSourceMap,
            requirements = validRequirements
        )

        assertTrue("Expected success, got failure instead", result is ProgramImageCreationResult.Success)
        val image = (result as ProgramImageCreationResult.Success).image

        // Mutate original map
        mutableSourceMap["main"] = "local x = 999"
        mutableSourceMap["extra"] = "local z = 3"

        // Verify image sourceMap was not mutated
        assertEquals("local x = 1", image.sourceMap["main"])
        assertEquals("local y = 2", image.sourceMap["helper"])
        assertTrue(image.sourceMap.containsKey("helper"))
        assertTrue(!image.sourceMap.containsKey("extra"))
        assertEquals(2, image.sourceMap.size)

        // Verify unmodifiableMap behaviour
        try {
            (image.sourceMap as MutableMap<String, String>)["main"] = "new"
            fail("Expected UnsupportedOperationException on mutating the returned sourceMap")
        } catch (e: UnsupportedOperationException) {
            // Expected
        }

        // Verify keys and values are distinct instances
        val origKey = mutableSourceMap.keys.first { it == "main" }
        val imageKey = image.sourceMap.keys.first { it == "main" }
        assertNotSame(origKey, imageKey)

        val origVal = mutableSourceMap["main"]!!
        val imageVal = image.sourceMap["main"]!!
        assertNotSame(origVal, imageVal)
    }

    @Test
    fun `ignored non-execution metadata does not affect validation`() {
        val result = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return {}"),
            requirements = validRequirements,
            metadata = mapOf(
                "author" to "someone",
                "version" to "1.0.0",
                "checksum" to "abcdef"
            )
        )

        assertTrue("Expected creation success with arbitrary metadata", result is ProgramImageCreationResult.Success)
    }

    @Test
    fun `malformed names are rejected by name regex`() {
        val validNames = listOf("a", "foo", "foo.bar", "foo_bar.baz_123")
        for (name in validNames) {
            val result = ImmutableProgramImage.create(
                entryPoint = name,
                sourceMap = mapOf(name to "return {}"),
                requirements = validRequirements
            )
            assertTrue("Expected name '$name' to be valid", result is ProgramImageCreationResult.Success)
        }

        val invalidNames = listOf(
            "",
            ".",
            "A",
            "foo.",
            ".foo",
            "foo..bar",
            "1foo",
            "_foo",
            "foo-bar",
            "foo.1bar",
            "foo._bar",
            "foo.A"
        )
        for (name in invalidNames) {
            val result = ImmutableProgramImage.create(
                entryPoint = "main",
                sourceMap = mapOf("main" to "return {}", name to "return {}"),
                requirements = validRequirements
            )

            assertTrue("Expected failure for invalid name '$name'", result is ProgramImageCreationResult.Failure)
            val err = (result as ProgramImageCreationResult.Failure).error
            assertTrue(
                "Expected InvalidModuleName for name '$name', got: $err",
                err is ProgramImageValidationError.InvalidModuleName
            )
            assertEquals(name, (err as ProgramImageValidationError.InvalidModuleName).name)
        }
    }

    @Test
    fun `reserved names are rejected`() {
        val reservedNames = listOf(
            "talkcan",
            "talkcan.foo",
            "talkcan.foo.bar"
        )

        for (name in reservedNames) {
            val result = ImmutableProgramImage.create(
                entryPoint = "main",
                sourceMap = mapOf("main" to "return {}", name to "return {}"),
                requirements = validRequirements
            )

            assertTrue("Expected failure for reserved name '$name'", result is ProgramImageCreationResult.Failure)
            val err = (result as ProgramImageCreationResult.Failure).error
            assertTrue(
                "Expected ReservedModuleName for reserved name '$name', got: $err",
                err is ProgramImageValidationError.ReservedModuleName
            )
            assertEquals(name, (err as ProgramImageValidationError.ReservedModuleName).name)
        }

        // Verify prefixes that are not strictly reserved
        val allowedPrefixes = listOf(
            "talkcan_foo",
            "nottalkcan",
            "foo.talkcan"
        )
        for (name in allowedPrefixes) {
            val result = ImmutableProgramImage.create(
                entryPoint = "main",
                sourceMap = mapOf("main" to "return {}", name to "return {}"),
                requirements = validRequirements
            )
            assertTrue("Expected allowed name '$name' to be successful, got: $result", result is ProgramImageCreationResult.Success)
        }
    }

    @Test
    fun `missing entry module fails validation`() {
        val result = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("helper" to "return {}"),
            requirements = validRequirements
        )

        assertTrue("Expected failure for missing entry module", result is ProgramImageCreationResult.Failure)
        val err = (result as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected MissingEntryModule error, got: $err",
            err is ProgramImageValidationError.MissingEntryModule
        )
        assertEquals("main", (err as ProgramImageValidationError.MissingEntryModule).entryPoint)
    }

    @Test
    fun `malformed source text with unpaired surrogates fails UTF-16 well-formedness`() {
        // Valid UTF-16 containing emoji (surrogate pair)
        val validSource = "local emoji = '\uD83D\uDE00'"
        val validResult = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to validSource),
            requirements = validRequirements
        )
        assertTrue("Expected valid emoji surrogate pair to succeed, got: $validResult", validResult is ProgramImageCreationResult.Success)

        // Unpaired high surrogate
        val malformedSource1 = "local bad = '\uD83D'"
        val result1 = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to malformedSource1),
            requirements = validRequirements
        )

        assertTrue("Expected failure for unpaired high surrogate", result1 is ProgramImageCreationResult.Failure)
        val err1 = (result1 as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected MalformedSourceText error for unpaired high surrogate, got: $err1",
            err1 is ProgramImageValidationError.MalformedSourceText
        )
        assertEquals("main", (err1 as ProgramImageValidationError.MalformedSourceText).moduleName)
        assertTrue(err1.message.contains("contains unpaired surrogates"))

        // Unpaired low surrogate
        val malformedSource2 = "local bad = '\uDE00'"
        val result2 = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to malformedSource2),
            requirements = validRequirements
        )

        assertTrue("Expected failure for unpaired low surrogate", result2 is ProgramImageCreationResult.Failure)
        val err2 = (result2 as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected MalformedSourceText error for unpaired low surrogate, got: $err2",
            err2 is ProgramImageValidationError.MalformedSourceText
        )
        assertEquals("main", (err2 as ProgramImageValidationError.MalformedSourceText).moduleName)
    }

    @Test
    fun `configured bounds checks limit module count, per-module size, and total size`() {
        val bounds = ValidationBounds(
            maxModuleCount = 2,
            maxModuleByteLength = 10,
            maxTotalByteLength = 15
        )

        // 1. Check maxModuleCount exceeded
        val tooManyModulesResult = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf(
                "main" to "1",
                "a" to "2",
                "b" to "3"
            ),
            requirements = validRequirements,
            bounds = bounds
        )
        assertTrue("Expected failure for maxModuleCount exceeded", tooManyModulesResult is ProgramImageCreationResult.Failure)
        val errCount = (tooManyModulesResult as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected TooManyModules, got: $errCount",
            errCount is ProgramImageValidationError.BoundsExceeded.TooManyModules
        )
        val specificErrCount = errCount as ProgramImageValidationError.BoundsExceeded.TooManyModules
        assertEquals(3, specificErrCount.count)
        assertEquals(2, specificErrCount.limit)

        // 2. Check maxModuleByteLength exceeded (with ASCII)
        val moduleTooLargeResult = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf(
                "main" to "12345678901" // 11 bytes
            ),
            requirements = validRequirements,
            bounds = bounds
        )
        assertTrue("Expected failure for maxModuleByteLength exceeded", moduleTooLargeResult is ProgramImageCreationResult.Failure)
        val errSize = (moduleTooLargeResult as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected ModuleTooLarge, got: $errSize",
            errSize is ProgramImageValidationError.BoundsExceeded.ModuleTooLarge
        )
        val specificErrSize = errSize as ProgramImageValidationError.BoundsExceeded.ModuleTooLarge
        assertEquals("main", specificErrSize.moduleName)
        assertEquals(11, specificErrSize.size)
        assertEquals(10, specificErrSize.limit)

        // 3. Check maxModuleByteLength exceeded (with multi-byte UTF-8 character)
        // 'ä' is 2 bytes in UTF-8. So "123456789ä" is 9 ASCII + 2 bytes = 11 bytes.
        val moduleTooLargeUtf8Result = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf(
                "main" to "123456789\u00E4"
            ),
            requirements = validRequirements,
            bounds = bounds
        )
        assertTrue("Expected failure for maxModuleByteLength exceeded via UTF-8", moduleTooLargeUtf8Result is ProgramImageCreationResult.Failure)
        val errSizeUtf8 = (moduleTooLargeUtf8Result as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected ModuleTooLarge for UTF-8 multi-byte string, got: $errSizeUtf8",
            errSizeUtf8 is ProgramImageValidationError.BoundsExceeded.ModuleTooLarge
        )
        val specificErrSizeUtf8 = errSizeUtf8 as ProgramImageValidationError.BoundsExceeded.ModuleTooLarge
        assertEquals("main", specificErrSizeUtf8.moduleName)
        assertEquals(11, specificErrSizeUtf8.size)
        assertEquals(10, specificErrSizeUtf8.limit)

        // 4. Check maxTotalByteLength exceeded
        val totalTooLargeResult = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf(
                "main" to "12345678", // 8 bytes
                "helper" to "12345678" // 8 bytes, total 16 bytes > 15
            ),
            requirements = validRequirements,
            bounds = bounds
        )
        assertTrue("Expected failure for maxTotalByteLength exceeded", totalTooLargeResult is ProgramImageCreationResult.Failure)
        val errTotal = (totalTooLargeResult as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected TotalSizeTooLarge, got: $errTotal",
            errTotal is ProgramImageValidationError.BoundsExceeded.TotalSizeTooLarge
        )
        val specificErrTotal = errTotal as ProgramImageValidationError.BoundsExceeded.TotalSizeTooLarge
        assertEquals(16, specificErrTotal.size)
        assertEquals(15, specificErrTotal.limit)
    }

    @Test
    fun `version mismatches are verified`() {
        // 1. luaVersion mismatch
        val badLuaRequirements = LuaProgramRequirements(
            luaVersion = "Lua 5.3",
            apiVersion = API_VERSION
        )
        val resultLua = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return {}"),
            requirements = badLuaRequirements
        )
        assertTrue("Expected failure for luaVersion mismatch", resultLua is ProgramImageCreationResult.Failure)
        val errLua = (resultLua as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected IncompatibleRequirements for luaVersion, got: $errLua",
            errLua is ProgramImageValidationError.IncompatibleRequirements
        )
        val specificErrLua = errLua as ProgramImageValidationError.IncompatibleRequirements
        assertEquals("luaVersion", specificErrLua.requirement)
        assertEquals("Lua 5.3", specificErrLua.requiredVersion)
        assertEquals(LUA_VERSION, specificErrLua.supportedVersion)

        // 2. apiVersion mismatch
        val badApiRequirements = LuaProgramRequirements(
            luaVersion = LUA_VERSION,
            apiVersion = "talkcan-lua-v2"
        )
        val resultApi = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return {}"),
            requirements = badApiRequirements
        )
        assertTrue("Expected failure for apiVersion mismatch", resultApi is ProgramImageCreationResult.Failure)
        val errApi = (resultApi as ProgramImageCreationResult.Failure).error
        assertTrue(
            "Expected IncompatibleRequirements for apiVersion, got: $errApi",
            errApi is ProgramImageValidationError.IncompatibleRequirements
        )
        val specificErrApi = errApi as ProgramImageValidationError.IncompatibleRequirements
        assertEquals("apiVersion", specificErrApi.requirement)
        assertEquals("talkcan-lua-v2", specificErrApi.requiredVersion)
        assertEquals(API_VERSION, specificErrApi.supportedVersion)
    }
}
