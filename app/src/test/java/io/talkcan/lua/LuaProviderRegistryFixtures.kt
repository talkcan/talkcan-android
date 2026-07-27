package io.talkcan.lua

/**
 * Immutable source images for provider/registry integration contracts.
 *
 * The JVM recording bridge recognizes the entry modules below as semantic kernel scenarios;
 * Android and Rust conformance must execute the same source through the native kernel.
 */
internal object LuaProviderRegistryFixtures {
    fun validCallbacks(): ImmutableProgramImage = image(
        entryPoint = "plugin.valid",
        sources = mapOf(
            "plugin.valid" to """
                local runtime = require("talkcan.runtime")
                return {
                    startup = function() end,
                    handle_readiness = function() return { ready = true } end,
                    handle_input = function(event) return { ok = true } end,
                }
            """.trimIndent(),
        ),
    )

    fun packageModules(): ImmutableProgramImage = image(
        entryPoint = "plugin.package_entry",
        sources = mapOf(
            "plugin.package_entry" to """
                local helper = require("plugin.helpers")
                return {
                    startup = function() helper.initialize() end,
                    handle_readiness = function() return { ready = helper.ready() } end,
                    handle_input = function(event) return { ok = true } end,
                }
            """.trimIndent(),
            "plugin.helpers" to """
                local initialized = false
                return {
                    initialize = function() initialized = true end,
                    ready = function() return initialized end,
                }
            """.trimIndent(),
        ),
    )

    fun proactiveTimer(): ImmutableProgramImage = image(
        entryPoint = "plugin.proactive",
        sources = mapOf(
            "plugin.proactive" to """
                local runtime = require("talkcan.runtime")
                return {
                    startup = function()
                        runtime.spawn(function()
                            while true do runtime.sleep(1.0) end
                        end)
                    end,
                }
            """.trimIndent(),
        ),
    )

    /** Input callback returns the normalized application-error branch. */
    fun normalizedError(): ImmutableProgramImage = image(
        entryPoint = "plugin.normalized_error",
        sources = mapOf(
            "plugin.normalized_error" to """
                return {
                    startup = function() end,
                    handle_readiness = function() return { ready = true } end,
                    handle_input = function(event)
                        return nil, { code = "E_INVALID_VALUE", detail = "invalid input" }
                    end,
                }
            """.trimIndent(),
        ),
    )


    /** Invalid module grammar is rejected from the source map before provider state creation. */
    fun sourceBound(): ProgramImageCreationResult = ImmutableProgramImage.create(
        entryPoint = "plugin.source_bound",
        sourceMap = mapOf(
            "plugin.source_bound" to "return { startup = function() end }",
            "plugin.bad-module" to "return {}",
        ),
        requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
    )
    fun entryEffectAttempt(): ImmutableProgramImage = image(
        entryPoint = "plugin.entry_effect",
        sources = mapOf(
            "plugin.entry_effect" to """
                local runtime = require("talkcan.runtime")
                runtime.spawn(function() end)
                return { startup = function() end }
            """.trimIndent(),
        ),
    )

    fun lazyModuleEffectAttempt(): ImmutableProgramImage = image(
        entryPoint = "plugin.lazy_effect_entry",
        sources = mapOf(
            "plugin.lazy_effect_entry" to """
                local lazy = require("plugin.lazy_effect")
                return { startup = function() lazy.run() end }
            """.trimIndent(),
            "plugin.lazy_effect" to """
                local log = require("talkcan.log")
                log.info({ message = "not permitted during load" })
                return { run = function() end }
            """.trimIndent(),
        ),
    )

    fun malformedCallbacks(): ImmutableProgramImage = image(
        entryPoint = "plugin.malformed_callbacks",
        sources = mapOf(
            "plugin.malformed_callbacks" to "return { handle_readiness = function() return { ready = true } end }",
        ),
    )

    /** A valid image used to hold deterministic replacement/close barriers in the recording bridge. */
    fun raceControl(): ImmutableProgramImage = image(
        entryPoint = "plugin.race_control",
        sources = mapOf(
            "plugin.race_control" to """
                return {
                    startup = function() end,
                    handle_readiness = function() return { ready = true } end,
                    handle_input = function(event) return { ok = true } end,
                }
            """.trimIndent(),
        ),
    )

    /** Deliberately incompatible requirements are rejected while creating the immutable image. */
    fun incompatibleApi(): ProgramImageCreationResult = ImmutableProgramImage.create(
        entryPoint = "plugin.incompatible",
        sourceMap = mapOf("plugin.incompatible" to "return { startup = function() end }"),
        requirements = LuaProgramRequirements(LUA_VERSION, "talkcan-lua-v999"),
    )

    private fun image(
        entryPoint: String,
        sources: Map<String, String>,
    ): ImmutableProgramImage = when (
        val result = ImmutableProgramImage.create(
            entryPoint = entryPoint,
            sourceMap = sources,
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        )
    ) {
        is ProgramImageCreationResult.Success -> result.image
        is ProgramImageCreationResult.Failure -> throw AssertionError("Invalid integration fixture: ${result.error.message}")
    }
}
