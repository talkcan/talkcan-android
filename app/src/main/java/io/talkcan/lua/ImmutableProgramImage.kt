package io.talkcan.lua

import java.util.Collections

public const val LUA_VERSION: String = "Lua 5.4"
public const val LUA_RELEASE: String = "5.4.8"
public const val API_VERSION: String = "talkcan-lua-v1"

public data class LuaProgramRequirements(
    val luaVersion: String,
    val apiVersion: String,
)

public sealed interface ImmutableProgramImage {
    val entryPoint: String
    val sourceMap: Map<String, String>
    val requirements: LuaProgramRequirements

    public companion object {
        public fun create(
            entryPoint: String,
            sourceMap: Map<String, String>,
            requirements: LuaProgramRequirements,
            metadata: Map<String, String> = emptyMap(),
            bounds: ValidationBounds = ValidationBounds.DEFAULT
        ): ProgramImageCreationResult {
            // Snapshot the source map and other values once at image construction defensively.
            val snapshottedEntryPoint = String(entryPoint.toCharArray())
            val snapshottedRequirements = LuaProgramRequirements(
                luaVersion = String(requirements.luaVersion.toCharArray()),
                apiVersion = String(requirements.apiVersion.toCharArray())
            )
            val snapshottedSourceMap = Collections.unmodifiableMap(
                sourceMap.entries.associate { (k, v) ->
                    String(k.toCharArray()) to String(v.toCharArray())
                }
            )

            val image = ImmutableProgramImageImpl(
                entryPoint = snapshottedEntryPoint,
                sourceMap = snapshottedSourceMap,
                requirements = snapshottedRequirements
            )

            return when (val validation = ProgramImageValidator.validate(image, bounds)) {
                is ProgramImageValidationResult.Success -> ProgramImageCreationResult.Success(image)
                is ProgramImageValidationResult.Failure -> ProgramImageCreationResult.Failure(validation.error)
            }
        }
    }
}

private class ImmutableProgramImageImpl(
    override val entryPoint: String,
    override val sourceMap: Map<String, String>,
    override val requirements: LuaProgramRequirements
) : ImmutableProgramImage

public sealed interface ProgramImageCreationResult {
    public data class Success(val image: ImmutableProgramImage) : ProgramImageCreationResult
    public data class Failure(val error: ProgramImageValidationError) : ProgramImageCreationResult
}

public sealed interface ProgramImageValidationError {
    val message: String

    public data class IncompatibleRequirements(
        val requirement: String,
        val requiredVersion: String,
        val supportedVersion: String
    ) : ProgramImageValidationError {
        override val message: String =
            "Incompatible requirement '$requirement': expected '$requiredVersion', but host supports '$supportedVersion'"
    }

    public data class MissingEntryModule(
        val entryPoint: String
    ) : ProgramImageValidationError {
        override val message: String =
            "Required entry point module '$entryPoint' is missing from the source map"
    }

    public data class InvalidModuleName(
        val name: String
    ) : ProgramImageValidationError {
        override val message: String =
            "Module name '$name' is invalid (must match canonical regex: [a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*)"
    }

    public data class ReservedModuleName(
        val name: String
    ) : ProgramImageValidationError {
        override val message: String =
            "Module name '$name' is reserved (cannot be 'talkcan' or start with 'talkcan.')"
    }

    public data class MalformedSourceText(
        val moduleName: String,
        val detail: String
    ) : ProgramImageValidationError {
        override val message: String =
            "Malformed source text in module '$moduleName': $detail"
    }

    public sealed interface BoundsExceeded : ProgramImageValidationError {
        public data class TooManyModules(
            val count: Int,
            val limit: Int
        ) : BoundsExceeded {
            override val message: String =
                "Source map entry count $count exceeds maximum limit $limit"
        }

        public data class ModuleTooLarge(
            val moduleName: String,
            val size: Int,
            val limit: Int
        ) : BoundsExceeded {
            override val message: String =
                "Source module '$moduleName' byte length $size exceeds limit $limit"
        }

        public data class TotalSizeTooLarge(
            val size: Int,
            val limit: Int
        ) : BoundsExceeded {
            override val message: String =
                "Total source map byte length $size exceeds limit $limit"
        }
    }
}
