package io.talkcan.storage

/**
 * Finite, host-configured bounds for a canonical mount-relative logical path.
 *
 * These are policy numbers, not Lua compatibility promises: the host may tighten them, but a
 * validated path is always within every bound. All three bounds are enforced during the single
 * parse pass, before any mount resolution or provider access.
 */
public data class PathBounds(
    /** Maximum number of `/`-separated components in one logical path. */
    val maxComponents: Int,
    /** Maximum UTF-8 bytes in one component. */
    val maxComponentBytes: Int,
    /** Maximum total UTF-8 bytes across the whole logical path, excluding separators. */
    val maxTotalBytes: Int,
) {
    init {
        require(maxComponents > 0) { "maxComponents must be positive" }
        require(maxComponentBytes > 0) { "maxComponentBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
    }

    public companion object {
        /** Default portable policy. Finite and bounded; not a Lua compatibility promise. */
        public val DEFAULT: PathBounds = PathBounds(
            maxComponents = 64,
            maxComponentBytes = 255,
            maxTotalBytes = 4096,
        )
    }
}

/**
 * A validated, canonical mount-relative logical path.
 *
 * The path is always relative to exactly one mount, uses `/` only as a virtual separator, and
 * contains one or more bounded canonical [components]. Instances are only produced by [parse];
 * there is no public constructor, so a [MountRelativePath] is proof that the raw input already
 * passed UTF-8, structural, and bounds validation. It never carries a platform path, URI, or
 * document identifier.
 */
public class MountRelativePath private constructor(
    /** The canonical components, in order. Never empty; no component is `.`, `..`, or empty. */
    public val components: List<String>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MountRelativePath && components == other.components)

    override fun hashCode(): Int = components.hashCode()

    override fun toString(): String = "MountRelativePath(${components.joinToString("/")})"

    public companion object {
        private const val SEPARATOR: Char = '/'
        private const val BACKSLASH: Char = '\\'
        private const val NUL: Char = '\u0000'

        /**
         * Parses [raw] into a canonical [MountRelativePath], enforcing [bounds] in a single
         * allocation-conscious pass.
         *
         * The parser rejects, before any mount resolution or provider access:
         *  - empty input and absolute paths (a leading `/`);
         *  - empty or repeated components (a trailing `/` or a `//` run);
         *  - `.` and `..` components and any cross-mount traversal;
         *  - NUL and backslash characters and platform-style paths;
         *  - invalid UTF-8 (unpaired surrogates in the input);
         *  - over-bound component count, component bytes, and total path bytes.
         *
         * UTF-8 byte lengths are computed directly from the UTF-16 input without materializing
         * a byte array for the whole path; only the canonical component strings are allocated.
         */
        public fun parse(raw: String, bounds: PathBounds = PathBounds.DEFAULT): PathParseResult {
            if (raw.isEmpty()) {
                return PathParseResult.Invalid(
                    FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "path is empty"),
                )
            }

            val components = ArrayList<String>(4)
            val current = StringBuilder()
            var componentBytes = 0
            var totalBytes = 0
            var componentHasContent = false
            var index = 0
            val length = raw.length

            while (index < length) {
                val unit = raw[index]
                when {
                    unit == SEPARATOR -> {
                        if (!componentHasContent) {
                            // Leading '/', a '//' run, or a trailing '/': an empty component,
                            // which also rejects absolute and platform paths like "/storage/…".
                            return PathParseResult.Invalid(
                                FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "empty path component"),
                            )
                        }
                        val finalized = finalize(current, componentBytes, bounds) ?: return invalidStructure()
                        if (components.size >= bounds.maxComponents) {
                            // Adding this component would exceed the component bound once the
                            // trailing component is also counted.
                            return PathParseResult.Invalid(
                                FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "too many path components"),
                            )
                        }
                        components.add(finalized)
                        current.setLength(0)
                        componentBytes = 0
                        componentHasContent = false
                        index++
                    }
                    unit == BACKSLASH -> return PathParseResult.Invalid(
                        FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "backslash is not permitted"),
                    )
                    unit == NUL -> return PathParseResult.Invalid(
                        FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "NUL is not permitted"),
                    )
                    unit.isHighSurrogate() -> {
                        val low = if (index + 1 < length) raw[index + 1] else '\u0000'
                        if (!low.isLowSurrogate()) {
                            return PathParseResult.Invalid(
                                FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "invalid UTF-8"),
                            )
                        }
                        // A supplementary code point is 4 UTF-8 bytes.
                        componentBytes += 4
                        totalBytes += 4
                        if (exceeds(componentBytes, totalBytes, bounds)) return invalidBounds(componentBytes, bounds)
                        current.append(unit).append(low)
                        componentHasContent = true
                        index += 2
                    }
                    unit.isLowSurrogate() -> return PathParseResult.Invalid(
                        FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "invalid UTF-8"),
                    )
                    else -> {
                        val code = unit.code
                        val bytes = when {
                            code < 0x80 -> 1
                            code < 0x800 -> 2
                            else -> 3
                        }
                        componentBytes += bytes
                        totalBytes += bytes
                        if (exceeds(componentBytes, totalBytes, bounds)) return invalidBounds(componentBytes, bounds)
                        current.append(unit)
                        componentHasContent = true
                        index++
                    }
                }
            }

            if (!componentHasContent) {
                // Empty input already returned above; reaching here means a trailing '/'.
                return PathParseResult.Invalid(
                    FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "empty path component"),
                )
            }
            val finalized = finalize(current, componentBytes, bounds) ?: return invalidStructure()
            if (components.size >= bounds.maxComponents) {
                return PathParseResult.Invalid(
                    FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "too many path components"),
                )
            }
            components.add(finalized)

            return PathParseResult.Valid(MountRelativePath(components))
        }

        /**
         * Validates one completed component. Returns the canonical string, or null when the
         * component is `.` or `..` (the only structural rejections left after per-unit checks).
         */
        private fun finalize(current: StringBuilder, componentBytes: Int, bounds: PathBounds): String? {
            if (componentBytes > bounds.maxComponentBytes) return null
            val value = current.toString()
            if (value == "." || value == "..") return null
            return value
        }

        private fun exceeds(componentBytes: Int, totalBytes: Int, bounds: PathBounds): Boolean =
            componentBytes > bounds.maxComponentBytes || totalBytes > bounds.maxTotalBytes

        private fun invalidBounds(componentBytes: Int, bounds: PathBounds): PathParseResult.Invalid =
            if (componentBytes > bounds.maxComponentBytes) {
                PathParseResult.Invalid(
                    FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "component exceeds byte bound"),
                )
            } else {
                PathParseResult.Invalid(
                    FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "path exceeds byte bound"),
                )
            }

        private fun invalidStructure(): PathParseResult.Invalid =
            PathParseResult.Invalid(
                FilesystemError(FilesystemErrorCode.E_INVALID_PATH, "dot path component is not permitted"),
            )
    }
}

/** The outcome of canonical path validation: a proven path or a normalized [FilesystemError]. */
public sealed interface PathParseResult {
    public data class Valid(val path: MountRelativePath) : PathParseResult
    public data class Invalid(val error: FilesystemError) : PathParseResult
}
