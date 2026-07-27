package io.talkcan.lua

public data class ValidationBounds(
    val maxModuleCount: Int,
    val maxModuleByteLength: Int,
    val maxTotalByteLength: Int
) {
    public companion object {
        public val DEFAULT = ValidationBounds(
            maxModuleCount = 128,
            maxModuleByteLength = 512 * 1024,
            maxTotalByteLength = 2 * 1024 * 1024
        )
    }
}

public sealed interface ProgramImageValidationResult {
    public data object Success : ProgramImageValidationResult
    public data class Failure(val error: ProgramImageValidationError) : ProgramImageValidationResult
}

public object ProgramImageValidator {
    private val CANONICAL_MODULE_REGEX = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*$")

    public fun validate(
        image: ImmutableProgramImage,
        bounds: ValidationBounds = ValidationBounds.DEFAULT
    ): ProgramImageValidationResult {
        // 1. Compatibility check
        if (image.requirements.luaVersion != LUA_VERSION) {
            return ProgramImageValidationResult.Failure(
                ProgramImageValidationError.IncompatibleRequirements(
                    requirement = "luaVersion",
                    requiredVersion = image.requirements.luaVersion,
                    supportedVersion = LUA_VERSION
                )
            )
        }
        if (image.requirements.apiVersion != API_VERSION) {
            return ProgramImageValidationResult.Failure(
                ProgramImageValidationError.IncompatibleRequirements(
                    requirement = "apiVersion",
                    requiredVersion = image.requirements.apiVersion,
                    supportedVersion = API_VERSION
                )
            )
        }

        // 2. Source map entry count bounds check
        val sourceMap = image.sourceMap
        if (sourceMap.size > bounds.maxModuleCount) {
            return ProgramImageValidationResult.Failure(
                ProgramImageValidationError.BoundsExceeded.TooManyModules(
                    count = sourceMap.size,
                    limit = bounds.maxModuleCount
                )
            )
        }

        // 3. Entry point presence check
        if (!sourceMap.containsKey(image.entryPoint)) {
            return ProgramImageValidationResult.Failure(
                ProgramImageValidationError.MissingEntryModule(image.entryPoint)
            )
        }

        // 4. Validate names, UTF-8 well-formedness, and per-module sizes
        var totalBytes = 0
        for ((name, source) in sourceMap) {
            // Check canonical name
            if (!CANONICAL_MODULE_REGEX.matches(name)) {
                return ProgramImageValidationResult.Failure(
                    ProgramImageValidationError.InvalidModuleName(name)
                )
            }

            // Check reserved name prefix
            if (name == "talkcan" || name.startsWith("talkcan.")) {
                return ProgramImageValidationResult.Failure(
                    ProgramImageValidationError.ReservedModuleName(name)
                )
            }

            // Check UTF-16 well-formedness (no unpaired surrogates)
            if (!isWellFormedUtf16(source)) {
                return ProgramImageValidationResult.Failure(
                    ProgramImageValidationError.MalformedSourceText(
                        moduleName = name,
                        detail = "contains unpaired surrogates"
                    )
                )
            }

            // Calculate UTF-8 byte length allocation-free
            val byteLength = calculateUtf8ByteLength(source)
            if (byteLength > bounds.maxModuleByteLength) {
                return ProgramImageValidationResult.Failure(
                    ProgramImageValidationError.BoundsExceeded.ModuleTooLarge(
                        moduleName = name,
                        size = byteLength,
                        limit = bounds.maxModuleByteLength
                    )
                )
            }

            totalBytes += byteLength
        }

        // 5. Total source map byte size check
        if (totalBytes > bounds.maxTotalByteLength) {
            return ProgramImageValidationResult.Failure(
                ProgramImageValidationError.BoundsExceeded.TotalSizeTooLarge(
                    size = totalBytes,
                    limit = bounds.maxTotalByteLength
                )
            )
        }

        return ProgramImageValidationResult.Success
    }

    private fun Char.isSurrogate(): Boolean = this in '\uD800'..'\uDFFF'
    private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'
    private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'

    private fun isWellFormedUtf16(str: String): Boolean {
        var i = 0
        val len = str.length
        while (i < len) {
            val c = str[i]
            if (c.isSurrogate()) {
                if (c.isHighSurrogate()) {
                    if (i + 1 < len && str[i + 1].isLowSurrogate()) {
                        i += 2
                    } else {
                        return false
                    }
                } else {
                    return false
                }
            } else {
                i++
            }
        }
        return true
    }

    private fun calculateUtf8ByteLength(sequence: CharSequence): Int {
        var count = 0
        var i = 0
        val len = sequence.length
        while (i < len) {
            val ch = sequence[i]
            if (ch.code < 0x80) {
                count++
                i++
            } else if (ch.code < 0x800) {
                count += 2
                i++
            } else if (ch.isSurrogate()) {
                if (ch.isHighSurrogate()) {
                    if (i + 1 < len && sequence[i + 1].isLowSurrogate()) {
                        count += 4
                        i += 2
                    } else {
                        count += 3
                        i++
                    }
                } else {
                    count += 3
                    i++
                }
            } else {
                count += 3
                i++
            }
        }
        return count
    }
}
