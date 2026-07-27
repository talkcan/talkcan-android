package io.talkcan.dependency

import io.talkcan.lua.ImmutableProgramImage
import io.talkcan.lua.LuaProgramRequirements
import io.talkcan.lua.ProgramImageCreationResult
import io.talkcan.lua.ProgramImageValidationError
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.ValidationBounds as LuaValidationBounds
import io.talkcan.dependency.PackageOutcome
import io.talkcan.dependency.PackageFailure
import io.talkcan.dependency.PackageFailure.FormatDetail
import io.talkcan.dependency.PackageFailure.IdentityDetail
import io.talkcan.dependency.PackageFailure.CompatibilityDetail
import io.talkcan.dependency.PackageFailure.IntegrityDetail
import io.talkcan.dependency.PackageFailure.MutationDetail
import io.talkcan.dependency.PackageFailure.StorageDetail
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileUiChoice
import io.talkcan.profile.ProfileUiControl
import io.talkcan.profile.ProfileUiFieldDeclaration
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

public object PackageValidator {

    private class MaxBytesExceededException : Exception()
    internal class DuplicateKeyException(message: String) : Exception(message)

    private val PERCENT_REGEX = Regex("%[0-9a-fA-F]{2}")
    private val MODULE_REGEX = Regex("^lua/([a-z][a-z0-9_]*/)*[a-z][a-z0-9_]*\\.lua$")
    private val CANONICAL_MODULE_REGEX = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*$")
    private val REPOSITORY_ID_REGEX = Regex("^[1-9][0-9]*$")
    private val ALLOWED_EXTRA_IDS = setOf(0x5455, 0x7875, 0x7855, 0x4b46, 0x7075)
    public fun validatePackage(
        artifactStream: InputStream,
        sourceRecord: PackageSourceRecord,
        stagingFile: File,
        bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT
    ): PackageOutcome<ValidatedPackageRevision> {
        // Step 1: Stage to the private staging file while hashing and checking maxArtifactBytes
        val digest = try {
            stageArtifact(artifactStream, stagingFile, bounds.maxArtifactBytes)
        } catch (e: MaxBytesExceededException) {
            try {
                deleteStagingFile(stagingFile)
            } catch (ioe: IOException) {
                return PackageOutcome.Failure(PackageFailure.Storage(StorageDetail.WRITE_FAILED))
            }
            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
        } catch (e: Exception) {
            try {
                deleteStagingFile(stagingFile)
            } catch (ioe: IOException) {
                return PackageOutcome.Failure(PackageFailure.Storage(StorageDetail.WRITE_FAILED))
            }
            val detail = when (e) {
                is IOException -> PackageFailure.Storage(StorageDetail.WRITE_FAILED)
                else -> PackageFailure.Mutation(MutationDetail.STAGE_FAILED)
            }
            return PackageOutcome.Failure(detail)
        }

        // Step 2: Validate the staged ZIP file using raw metadata parser
        return try {
            val validationResult = validateStagedZip(stagingFile, sourceRecord, bounds, digest)
            if (validationResult is PackageOutcome.Failure) {
                try {
                    deleteStagingFile(stagingFile)
                } catch (ioe: IOException) {
                    return PackageOutcome.Failure(PackageFailure.Storage(StorageDetail.WRITE_FAILED))
                }
            }
            validationResult
        } catch (e: Exception) {
            try {
                deleteStagingFile(stagingFile)
            } catch (ioe: IOException) {
                return PackageOutcome.Failure(PackageFailure.Storage(StorageDetail.WRITE_FAILED))
            }
            val detail = when (e) {
                is IOException -> PackageFailure.Storage(StorageDetail.WRITE_FAILED)
                else -> PackageFailure.Format(FormatDetail.INVALID_ZIP)
            }
            PackageOutcome.Failure(detail)
        }
    }

    private fun stageArtifact(
        inputStream: InputStream,
        stagingFile: File,
        maxBytes: Long
    ): ArtifactDigest {
        val parent = stagingFile.parentFile
        if (parent != null && !parent.exists()) {
            val created = parent.mkdirs()
            if (!created && !parent.exists()) {
                throw IOException("Failed to create parent directory")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytesRead = 0L
        stagingFile.outputStream().use { fos ->
            val buffer = ByteArray(8192)
            while (true) {
                val remainingLimit = maxBytes - totalBytesRead
                if (remainingLimit < 0) {
                    throw MaxBytesExceededException()
                }
                if (remainingLimit == 0L) {
                    val testRead = inputStream.read()
                    if (testRead != -1) {
                        throw MaxBytesExceededException()
                    }
                    break
                }
                val bytesToRead = minOf(buffer.size.toLong(), remainingLimit).toInt()
                val read = inputStream.read(buffer, 0, bytesToRead)
                if (read == -1) {
                    break
                }
                fos.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                totalBytesRead += read
                if (totalBytesRead > maxBytes) {
                    throw MaxBytesExceededException()
                }
            }
        }
        val digestBytes = digest.digest()
        val digestHex = digestBytes.joinToString("") { "%02x".format(it) }
        return ArtifactDigest(digestHex)
    }

    private fun deleteStagingFile(file: File) {
        if (file.exists()) {
            val deleted = file.delete()
            if (!deleted && file.exists()) {
                throw IOException("Failed to delete staging file")
            }
        }
    }

    private class ZipEntryInfo(
        val name: String,
        val versionNeeded: Int,
        val method: Int,
        val flags: Int,
        val lastModTime: Int,
        val lastModDate: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val localHeaderOffset: Long,
        val isDirectory: Boolean,
        val extraBytes: ByteArray
    )

    private class LocalRange(val start: Long, val end: Long)

    private fun readUnsignedShort(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        if (b1 == -1 || b2 == -1) throw IOException("Unexpected EOF")
        return (b2 shl 8) or b1
    }

    private fun readInt(raf: RandomAccessFile): Long {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        if (b1 == -1 || b2 == -1 || b3 == -1 || b4 == -1) throw IOException("Unexpected EOF")
        return ((b4.toLong() and 0xFF) shl 24) or
               ((b3.toLong() and 0xFF) shl 16) or
               ((b2.toLong() and 0xFF) shl 8) or
               (b1.toLong() and 0xFF)
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val fileLength = raf.length()
        val minOffset = maxOf(0L, fileLength - 65535L - 22L)
        var offset = fileLength - 22L
        val buffer = ByteArray(4)
        while (offset >= minOffset) {
            raf.seek(offset)
            raf.readFully(buffer)
            if (buffer[0] == 0x50.toByte() && buffer[1] == 0x4B.toByte() &&
                buffer[2] == 0x05.toByte() && buffer[3] == 0x06.toByte()) {
                return offset
            }
            offset--
        }
        throw IOException("EOCD signature not found")
    }

    internal fun validateStagedZip(
        file: File,
        sourceRecord: PackageSourceRecord,
        bounds: PackageValidationBounds,
        digest: ArtifactDigest
    ): PackageOutcome<ValidatedPackageRevision> {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 22) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
            }
            val eocdOffset = try {
                findEocd(raf)
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
            }

            raf.seek(eocdOffset)
            val eocdSig = readInt(raf) // 0x06054b50
            val diskNumber = readUnsignedShort(raf)
            val diskStart = readUnsignedShort(raf)
            val numEntriesOnDisk = readUnsignedShort(raf)
            val numEntriesTotal = readUnsignedShort(raf)
            val centralDirSize = readInt(raf)
            val centralDirOffset = readInt(raf)
            val commentLength = readUnsignedShort(raf)

            // Validate EOCD comment reaches exact EOF
            if (eocdOffset + 22 + commentLength != fileLength) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
            }

            // Central directory ends exactly at EOCD
            if (centralDirOffset + centralDirSize != eocdOffset) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
            }

            if (diskNumber != 0 || diskStart != 0 || numEntriesOnDisk != numEntriesTotal) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP)) // multi-disk unsupported
            }

            if (numEntriesTotal > bounds.maxEntryCount) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
            }

            if (numEntriesTotal == 0xFFFF || centralDirOffset == 0xFFFFFFFFL || centralDirSize == 0xFFFFFFFFL) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP)) // ZIP64 unsupported
            }

            val entries = ArrayList<ZipEntryInfo>()
            val seenNames = HashSet<String>()
            val caseFoldedNames = HashSet<String>()
            val localRanges = ArrayList<LocalRange>()

            var currentCenOffset = centralDirOffset
            for (i in 0 until numEntriesTotal) {
                if (currentCenOffset + 46 > centralDirOffset + centralDirSize) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }
                raf.seek(currentCenOffset)
                val cenSig = readInt(raf)
                if (cenSig != 0x02014b50L) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }
                val versionMadeBy = readUnsignedShort(raf)
                val versionNeeded = readUnsignedShort(raf)
                val flags = readUnsignedShort(raf)
                val method = readUnsignedShort(raf)
                val lastModTime = readUnsignedShort(raf)
                val lastModDate = readUnsignedShort(raf)
                val entryCrc = readInt(raf)
                val compressedSize = readInt(raf)
                val uncompressedSize = readInt(raf)
                val nameLength = readUnsignedShort(raf)
                val extraLength = readUnsignedShort(raf)
                val commentLengthVal = readUnsignedShort(raf)
                val diskNumberStart = readUnsignedShort(raf)
                val internalAttr = readUnsignedShort(raf)
                val externalAttr = readInt(raf)
                val localHeaderOffset = readInt(raf)

                if (diskNumberStart != 0) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                // Reject unknown/unsupported GP flags
                val allowedFlagsMask = if (method == 8) {
                    (1 shl 1) or (1 shl 2) or (1 shl 11)
                } else {
                    1 shl 11
                }
                if ((flags and allowedFlagsMask.inv()) != 0) {
                    if ((flags and 1) != 0 || (flags and 64) != 0 || (flags and 8192) != 0) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.ENCRYPTED_ENTRY))
                    }
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                if (method != 0 && method != 8) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNSUPPORTED_COMPRESSION))
                }

                // STORED requires csize == usize
                if (method == 0 && compressedSize != uncompressedSize) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                val hostOs = (versionMadeBy ushr 8) and 0xFF
                if (hostOs != 3) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP)) // require UNIX attributes
                }

                val unixMode = (externalAttr ushr 16).toInt() and 0xFFFF
                val fileType = unixMode and 0xF000
                val isDir = fileType == 0x4000
                val isReg = fileType == 0x8000
                if (!isDir && !isReg) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNEXPECTED_ENTRY)) // symlinks, FIFO, etc. prohibited
                }

                if (isReg && (unixMode and 0x0049) != 0) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNEXPECTED_ENTRY)) // executable prohibited
                }

                // Enforce directory zero sizes and CRC
                if (isDir) {
                    if (compressedSize != 0L || uncompressedSize != 0L || entryCrc != 0L) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                }

                val nameBytes = ByteArray(nameLength)
                raf.readFully(nameBytes)

                // Extra field parsing & rejection of ZIP64/NTFS/unsupported extra fields
                val extraBytes = ByteArray(extraLength)
                raf.readFully(extraBytes)
                var extraOffset = 0
                while (extraOffset + 4 <= extraBytes.size) {
                    val headerId = (extraBytes[extraOffset].toInt() and 0xFF) or ((extraBytes[extraOffset + 1].toInt() and 0xFF) shl 8)
                    val dataSize = (extraBytes[extraOffset + 2].toInt() and 0xFF) or ((extraBytes[extraOffset + 3].toInt() and 0xFF) shl 8)
                    if (extraOffset + 4 + dataSize > extraBytes.size) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                    if (headerId == 0x0001) { // ZIP64
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                    if (!ALLOWED_EXTRA_IDS.contains(headerId)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                    extraOffset += 4 + dataSize
                }
                if (extraOffset != extraBytes.size) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                // Skip comment bytes
                raf.skipBytes(commentLengthVal)

                if (nameBytes.size > bounds.maxPathBytes) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
                }

                val name = try {
                    decodeStrictUtf8(nameBytes)
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                // Strictly validate path components
                if (name.startsWith("\uFEFF") || name.contains("\u0000") || name.contains("\\")) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
                }

                if (name.contains("%2f", ignoreCase = true) || name.contains("%5c", ignoreCase = true)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
                }
                if (PERCENT_REGEX.containsMatchIn(name)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
                }

                if (!Normalizer.isNormalized(name, Normalizer.Form.NFC)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
                }

                val segments = name.split('/')
                for (segment in segments) {
                    if (segment == "." || segment == "..") {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
                    }
                }

                if (name.startsWith("/") || name.contains("//")) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
                }

                if (isDir && !name.endsWith("/")) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNEXPECTED_ENTRY))
                }

                if (isReg && name.endsWith("/")) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNEXPECTED_ENTRY))
                }

                if (!seenNames.add(name)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.COLLISION))
                }

                val lowerName = name.lowercase(Locale.ROOT)
                if (!caseFoldedNames.add(lowerName)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.COLLISION))
                }

                // Check ratio if compressedSize > 0
                if (compressedSize > 0) {
                    val ratio = uncompressedSize.toDouble() / compressedSize.toDouble()
                    if (ratio > bounds.maxExpansionRatio) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
                    }
                }

                // Enforce sizes bounds
                if (compressedSize > bounds.maxArtifactBytes) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
                }

                entries.add(
                    ZipEntryInfo(
                        name = name,
                        versionNeeded = versionNeeded,
                        method = method,
                        flags = flags,
                        lastModTime = lastModTime,
                        lastModDate = lastModDate,
                        compressedSize = compressedSize,
                        uncompressedSize = uncompressedSize,
                        crc = entryCrc,
                        localHeaderOffset = localHeaderOffset,
                        isDirectory = isDir,
                        extraBytes = extraBytes
                    )
                )
                currentCenOffset += 46 + nameLength + extraLength + commentLengthVal
            }

            // Central directory cursor stays exact
            if (currentCenOffset != centralDirOffset + centralDirSize) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
            }


            // Group entries and validate directory structures
            val regularEntries = entries.filter { !it.isDirectory }
            val directoryEntries = entries.filter { it.isDirectory }

            val expectedParentDirs = HashSet<String>()

            for (entry in regularEntries) {
                if (entry.name == "manifest.json") {
                    if (entry.uncompressedSize > bounds.maxManifestBytes) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
                    }
                } else if (MODULE_REGEX.matches(entry.name)) {
                    if (entry.uncompressedSize > bounds.maxPerModuleBytes) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
                    }
                    val parts = entry.name.split('/')
                    var currentParent = ""
                    for (j in 0 until parts.size - 1) {
                        currentParent += parts[j] + "/"
                        expectedParentDirs.add(currentParent)
                    }
                } else {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNEXPECTED_ENTRY))
                }
            }

            // Verify directory entries exist only for canonical parents
            for (dirEntry in directoryEntries) {
                if (!expectedParentDirs.contains(dirEntry.name)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNEXPECTED_ENTRY))
                }
            }

            // Total source size check
            var totalSourceBytes = 0L
            for (entry in regularEntries) {
                if (entry.name != "manifest.json") {
                    totalSourceBytes += entry.uncompressedSize
                    if (totalSourceBytes > bounds.maxTotalSourceBytes) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED))
                    }
                }
            }

            // Verify every local header including directories
            var manifestBytes: ByteArray? = null
            val luaSourceMap = LinkedHashMap<String, String>()

            for (entry in entries) {
                raf.seek(entry.localHeaderOffset)
                val locSig = readInt(raf)
                if (locSig != 0x04034b50L) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }
                val locVersionNeeded = readUnsignedShort(raf)
                val locFlags = readUnsignedShort(raf)
                val locMethod = readUnsignedShort(raf)
                val locLastModTime = readUnsignedShort(raf)
                val locLastModDate = readUnsignedShort(raf)
                val locCrc = readInt(raf)
                val locCompressedSize = readInt(raf)
                val locUncompressedSize = readInt(raf)
                val locNameLength = readUnsignedShort(raf)
                val locExtraLength = readUnsignedShort(raf)

                // Compare version needed, flags, method, time, date, CRC, and sizes
                if (locVersionNeeded != entry.versionNeeded ||
                    locMethod != entry.method || locFlags != entry.flags || locCrc != entry.crc ||
                    locCompressedSize != entry.compressedSize || locUncompressedSize != entry.uncompressedSize ||
                    locLastModTime != entry.lastModTime || locLastModDate != entry.lastModDate) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                val locNameBytes = ByteArray(locNameLength)
                raf.readFully(locNameBytes)
                if (!locNameBytes.contentEquals(entry.name.toByteArray(StandardCharsets.UTF_8))) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                // Parse/reject unsupported extra fields in local header, and check content equality
                val locExtraBytes = ByteArray(locExtraLength)
                raf.readFully(locExtraBytes)
                if (!locExtraBytes.contentEquals(entry.extraBytes)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }
                var locExtraOffset = 0
                while (locExtraOffset + 4 <= locExtraBytes.size) {
                    val headerId = (locExtraBytes[locExtraOffset].toInt() and 0xFF) or ((locExtraBytes[locExtraOffset + 1].toInt() and 0xFF) shl 8)
                    val dataSize = (locExtraBytes[locExtraOffset + 2].toInt() and 0xFF) or ((locExtraBytes[locExtraOffset + 3].toInt() and 0xFF) shl 8)
                    if (locExtraOffset + 4 + dataSize > locExtraBytes.size) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                    if (headerId == 0x0001) { // ZIP64
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                    if (!ALLOWED_EXTRA_IDS.contains(headerId)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                    }
                    locExtraOffset += 4 + dataSize
                }
                if (locExtraOffset != locExtraBytes.size) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }

                // Compute local range from verified local lengths
                val startRange = entry.localHeaderOffset
                val endRange = entry.localHeaderOffset + 30 + locNameLength + locExtraLength + entry.compressedSize
                if (startRange < 0 || endRange > centralDirOffset) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }
                localRanges.add(LocalRange(startRange, endRange))
                if (!entry.isDirectory) {
                    // Content extraction
                    val contentBytes = try {
                        decompressEntry(raf, entry, locExtraLength)
                    } catch (e: Exception) {
                        return PackageOutcome.Failure(PackageFailure.Integrity(IntegrityDetail.CORRUPTED_ARCHIVE))
                    }

                    if (entry.name == "manifest.json") {
                        manifestBytes = contentBytes
                    } else {
                        // Check Lua binary signature
                        if (contentBytes.size >= 4 &&
                            contentBytes[0] == 0x1B.toByte() && contentBytes[1] == 0x4C.toByte() &&
                            contentBytes[2] == 0x75.toByte() && contentBytes[3] == 0x61.toByte()) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.BYTECODE_PROHIBITED))
                        }
                        val sourceText = try {
                            decodeStrictUtf8(contentBytes)
                        } catch (e: Exception) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                        }
                        val modulePath = entry.name.removePrefix("lua/").removeSuffix(".lua")
                        val moduleName = modulePath.replace('/', '.')
                        luaSourceMap[moduleName] = sourceText
                    }
                }
            }

            // Verify local ranges are non-overlapping
            localRanges.sortBy { it.start }
            for (j in 1 until localRanges.size) {
                if (localRanges[j].start < localRanges[j - 1].end) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ZIP))
                }
            }

            if (manifestBytes == null) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MISSING_MANIFEST))
            }

            val manifestText = try {
                decodeStrictUtf8(manifestBytes)
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            // Parse manifest JSON strictly
            val manifestMap = try {
                StrictJsonParser(manifestText).parse()
            } catch (e: DuplicateKeyException) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.DUPLICATE_KEYS))
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            // Validate manifest structure and exact v1 constraints
            val allowedRootKeys = setOf(
                "manifestVersion", "repositoryId", "packageVersion", "entryModule", "presentation", "runtime", "configuration", "resources", "profileTypes", "choiceResolvers", "workQueues", "capabilities"
            )
            if (manifestMap.keys != allowedRootKeys) {
                val missing = allowedRootKeys - manifestMap.keys
                val extra = manifestMap.keys - allowedRootKeys
                if (extra.isNotEmpty()) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val manifestVersion = manifestMap["manifestVersion"] as? Int
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (manifestVersion != 1) {
                return PackageOutcome.Failure(PackageFailure.Compatibility(CompatibilityDetail.UNSUPPORTED_MANIFEST_VERSION))
            }

            val repositoryIdStr = manifestMap["repositoryId"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (!repositoryIdStr.matches(REPOSITORY_ID_REGEX)) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            if (repositoryIdStr != sourceRecord.repositoryId.value) {
                return PackageOutcome.Failure(PackageFailure.Identity(IdentityDetail.REPOSITORY_ID_MISMATCH))
            }

            val packageVersion = manifestMap["packageVersion"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (packageVersion.isBlank()) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val entryModule = manifestMap["entryModule"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (entryModule.isBlank() || !entryModule.matches(CANONICAL_MODULE_REGEX)) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR))
            }

            val presentationMap = manifestMap["presentation"] as? Map<*, *>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val allowedPresentationKeys = setOf("label", "summary")
            if (presentationMap.keys != allowedPresentationKeys) {
                val extra = presentationMap.keys - allowedPresentationKeys
                if (extra.isNotEmpty()) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val label = presentationMap["label"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val summary = presentationMap["summary"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (label.isBlank() || summary.isBlank()) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val runtimeMap = manifestMap["runtime"] as? Map<*, *>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val allowedRuntimeKeys = setOf("luaVersion", "apiVersion")
            if (runtimeMap.keys != allowedRuntimeKeys) {
                val extra = runtimeMap.keys - allowedRuntimeKeys
                if (extra.isNotEmpty()) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val luaVersion = runtimeMap["luaVersion"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val apiVersion = runtimeMap["apiVersion"] as? String
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (luaVersion != LUA_VERSION) {
                return PackageOutcome.Failure(PackageFailure.Compatibility(CompatibilityDetail.LUA_VERSION_INCOMPATIBLE))
            }
            if (apiVersion != API_VERSION) {
                return PackageOutcome.Failure(PackageFailure.Compatibility(CompatibilityDetail.API_VERSION_INCOMPATIBLE))
            }

            if (!manifestMap.containsKey("configuration") || !manifestMap.containsKey("capabilities") || !manifestMap.containsKey("resources")) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val capabilitiesRaw = manifestMap["capabilities"]
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (capabilitiesRaw !is List<*>) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val capabilities = LinkedHashSet<String>()
            for (cap in capabilitiesRaw) {
                if (cap !is String) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                if (!PackageCapability.ALL.contains(cap)) {
                    return PackageOutcome.Failure(PackageFailure.Capability(PackageFailure.CapabilityDetail.UNKNOWN_CAPABILITY_ID))
                }
                if (!capabilities.add(cap)) {
                    return PackageOutcome.Failure(PackageFailure.Capability(PackageFailure.CapabilityDetail.DUPLICATE_CAPABILITY_ID))
                }
            }

            // Revised v1: parse the exact required profileTypes declaration array.
            val profileTypesRaw = manifestMap["profileTypes"]
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (profileTypesRaw !is List<*>) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val profileTypes = ArrayList<ProfileTypeDeclaration>()
            for (typeItem in profileTypesRaw) {
                val typeMap = typeItem as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val allowedTypeKeys = setOf("id", "label", "help", "schemaVersion", "data", "ui")
                val requiredTypeKeys = setOf("id", "label", "schemaVersion", "data", "ui")
                for (k in typeMap.keys) {
                    if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (!allowedTypeKeys.contains(k)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                }
                for (k in requiredTypeKeys) {
                    if (!typeMap.containsKey(k)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                }
                val typeId = typeMap["id"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val typeLabel = typeMap["label"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val typeHelp = if (typeMap.containsKey("help")) {
                    val hVal = typeMap["help"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (hVal.isBlank()) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                    hVal
                } else {
                    null
                }
                val typeSchemaVersion = typeMap["schemaVersion"] as? Int
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

                val typeDataMap = typeMap["data"] as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                for (k in typeDataMap.keys) {
                    if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (k != "fields" && k != "additionalProperties") {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                }
                if (!typeDataMap.containsKey("additionalProperties") ||
                    typeDataMap["additionalProperties"] != false
                ) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                val typeDataFieldsRaw = typeDataMap["fields"] as? List<*>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val profileDataFields = ArrayList<ProfileFieldDeclaration>()
                for (fieldItem in typeDataFieldsRaw) {
                    val fieldMap = fieldItem as? Map<*, *>
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val fieldTypeStr = fieldMap["type"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val allowedFieldKeys = when (fieldTypeStr) {
                        "string" -> setOf("id", "type", "required", "default", "allowedValues")
                        "boolean" -> setOf("id", "type", "required", "default")
                        "integer" -> setOf("id", "type", "required", "default", "minimum", "maximum")
                        "secret" -> setOf("id", "type", "required")
                        else -> return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                    for (k in fieldMap.keys) {
                        if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        if (!allowedFieldKeys.contains(k)) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                        }
                    }
                    val fieldId = fieldMap["id"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val fieldRequired = fieldMap["required"] as? Boolean
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val profileField = when (fieldTypeStr) {
                        "string" -> {
                            val defaultStr = if (fieldMap.containsKey("default")) {
                                fieldMap["default"] as? String
                                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            } else {
                                null
                            }
                            val allowedValues = if (fieldMap.containsKey("allowedValues")) {
                                val avRaw = fieldMap["allowedValues"] as? List<*>
                                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                                val avList = ArrayList<String>()
                                for (av in avRaw) {
                                    if (av !is String) {
                                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                                    }
                                    avList.add(av)
                                }
                                avList
                            } else {
                                null
                            }
                            try {
                                ProfileFieldDeclaration.StringField(fieldId, fieldRequired, defaultStr, allowedValues)
                            } catch (e: Exception) {
                                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            }
                        }
                        "boolean" -> {
                            val defaultBool = if (fieldMap.containsKey("default")) {
                                fieldMap["default"] as? Boolean
                                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            } else {
                                null
                            }
                            try {
                                ProfileFieldDeclaration.BooleanField(fieldId, fieldRequired, defaultBool)
                            } catch (e: Exception) {
                                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            }
                        }
                        "integer" -> {
                            val defaultLong = if (fieldMap.containsKey("default")) {
                                asLong(fieldMap["default"])
                                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            } else {
                                null
                            }
                            val minimum = if (fieldMap.containsKey("minimum")) {
                                asLong(fieldMap["minimum"])
                                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            } else {
                                null
                            }
                            val maximum = if (fieldMap.containsKey("maximum")) {
                                asLong(fieldMap["maximum"])
                                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            } else {
                                null
                            }
                            try {
                                ProfileFieldDeclaration.IntegerField(fieldId, fieldRequired, defaultLong, minimum, maximum)
                            } catch (e: Exception) {
                                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            }
                        }
                        else -> {
                            try {
                                ProfileFieldDeclaration.SecretField(fieldId, fieldRequired)
                            } catch (e: Exception) {
                                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            }
                        }
                    }
                    profileDataFields.add(profileField)
                }

                val typeUiMap = typeMap["ui"] as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                if (typeUiMap.keys != setOf("fields")) {
                    val extra = typeUiMap.keys - setOf("fields")
                    if (extra.isNotEmpty()) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                val typeUiFieldsRaw = typeUiMap["fields"] as? List<*>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val profileUiFields = ArrayList<ProfileUiFieldDeclaration>()
                for (uiItem in typeUiFieldsRaw) {
                    val uiMap = uiItem as? Map<*, *>
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val controlStr = uiMap["control"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val control = ProfileUiControl.entries.firstOrNull { it.value == controlStr }
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val allowedUiKeys = if (control == ProfileUiControl.CHOICE) {
                        setOf("field", "control", "label", "help", "choices")
                    } else {
                        setOf("field", "control", "label", "help")
                    }
                    for (k in uiMap.keys) {
                        if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        if (!allowedUiKeys.contains(k)) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                        }
                    }
                    val uiFieldId = uiMap["field"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val uiLabel = uiMap["label"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val uiHelp = if (uiMap.containsKey("help")) {
                        val hVal = uiMap["help"] as? String
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        if (hVal.isBlank()) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                        hVal
                    } else {
                        null
                    }
                    val uiChoices = if (control == ProfileUiControl.CHOICE) {
                        val choicesRaw = uiMap["choices"] as? List<*>
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        val choicesList = ArrayList<ProfileUiChoice>()
                        for (choiceItem in choicesRaw) {
                            val choiceMap = choiceItem as? Map<*, *>
                                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            for (k in choiceMap.keys) {
                                if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                                if (k != "value" && k != "label") {
                                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                                }
                            }
                            val cVal = choiceMap["value"] as? String
                                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            val cLabel = choiceMap["label"] as? String
                                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            try {
                                choicesList.add(ProfileUiChoice(cVal, cLabel))
                            } catch (e: Exception) {
                                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            }
                        }
                        choicesList
                    } else {
                        null
                    }
                    try {
                        profileUiFields.add(ProfileUiFieldDeclaration(uiFieldId, control, uiLabel, uiHelp, uiChoices))
                    } catch (e: Exception) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                }

                val profileSchema = try {
                    ProfileSchema(profileDataFields, profileUiFields)
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                val profileType = try {
                    ProfileTypeDeclaration(typeId, typeLabel, typeHelp, typeSchemaVersion, profileSchema)
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                profileTypes.add(profileType)
            }

            // Secret-bearing profile schemas require declared secret-read authority.
            if (profileTypes.any { it.schema.secretFieldIds().isNotEmpty() } &&
                !capabilities.contains(PackageCapability.SECRETS_READ)
            ) {
                return PackageOutcome.Failure(PackageFailure.Capability(PackageFailure.CapabilityDetail.SECRET_MISSING_CAPABILITY))
            }

            // Revised v1: parse the exact required choiceResolvers declaration array.
            val choiceResolversRaw = manifestMap["choiceResolvers"]
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (choiceResolversRaw !is List<*>) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val choiceResolvers = ArrayList<ChoiceResolverDeclaration>()
            for (resolverItem in choiceResolversRaw) {
                val resolverMap = resolverItem as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val allowedResolverKeys = setOf("id", "module", "capabilities")
                if (resolverMap.keys != allowedResolverKeys) {
                    val extra = resolverMap.keys - allowedResolverKeys
                    if (extra.isNotEmpty()) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                val resolverId = resolverMap["id"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val resolverModule = resolverMap["module"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val resolverCapsRaw = resolverMap["capabilities"] as? List<*>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val resolverCaps = LinkedHashSet<String>()
                for (cap in resolverCapsRaw) {
                    if (cap !is String) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                    if (!PackageCapability.RESOLVER_ELIGIBLE.contains(cap)) {
                        return PackageOutcome.Failure(PackageFailure.Capability(PackageFailure.CapabilityDetail.RESOLVER_CAPABILITY_INELIGIBLE))
                    }
                    if (!resolverCaps.add(cap)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                    if (!capabilities.contains(cap)) {
                        return PackageOutcome.Failure(PackageFailure.Capability(PackageFailure.CapabilityDetail.RESOLVER_CAPABILITY_UNDECLARED))
                    }
                }
                val resolver = try {
                    ChoiceResolverDeclaration(resolverId, resolverModule, resolverCaps)
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                choiceResolvers.add(resolver)
            }

            // Revised v1: parse the exact required workQueues declaration array.
            val workQueuesRaw = manifestMap["workQueues"]
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (workQueuesRaw !is List<*>) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val workQueues = ArrayList<WorkQueueDeclaration>()
            for (queueItem in workQueuesRaw) {
                val queueId = queueItem as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val queue = try {
                    WorkQueueDeclaration(queueId)
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                workQueues.add(queue)
            }
            // Durable queue declarations require declared work authority.
            if (workQueues.isNotEmpty() && !capabilities.contains(PackageCapability.WORK_QUEUE)) {
                return PackageOutcome.Failure(PackageFailure.Capability(PackageFailure.CapabilityDetail.WORK_QUEUE_MISSING_CAPABILITY))
            }

            val resourcesMap = manifestMap["resources"] as? Map<*, *>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val allowedResourceKeys = setOf("mounts")
            if (resourcesMap.keys != allowedResourceKeys) {
                val extra = resourcesMap.keys - allowedResourceKeys
                if (extra.isNotEmpty()) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val mountsRaw = resourcesMap["mounts"] as? List<*>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val parsedMounts = ArrayList<PackageMountDeclaration>()
            for (mountItem in mountsRaw) {
                val mountMap = mountItem as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val requiredMountKeys = setOf("id", "kind", "access", "required", "label")
                val allowedMountKeys = setOf("id", "kind", "access", "required", "label", "help")
                for (k in mountMap.keys) {
                    if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (!allowedMountKeys.contains(k)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                }
                for (k in requiredMountKeys) {
                    if (!mountMap.containsKey(k)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                }
                val mountId = mountMap["id"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val kindStr = mountMap["kind"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val kind = PackageMountKind.entries.firstOrNull { it.value == kindStr }
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val accessStr = mountMap["access"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val access = PackageMountAccess.entries.firstOrNull { it.value == accessStr }
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val requiredFlag = mountMap["required"] as? Boolean
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val mountLabel = mountMap["label"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val mountHelp = if (mountMap.containsKey("help")) {
                    mountMap["help"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                } else {
                    null
                }
                val mount = try {
                    PackageMountDeclaration(mountId, kind, access, requiredFlag, mountLabel, mountHelp)
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                parsedMounts.add(mount)
            }
            val resources = try {
                PackageResourcesDeclaration(parsedMounts)
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val configurationMap = manifestMap["configuration"] as? Map<*, *>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

            val allowedConfigKeys = setOf("schemaVersion", "data", "ui")
            if (configurationMap.keys != allowedConfigKeys) {
                val extra = configurationMap.keys - allowedConfigKeys
                if (extra.isNotEmpty()) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val configSchemaVersion = configurationMap["schemaVersion"] as? Int
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            if (configSchemaVersion != 1) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val dataMap = configurationMap["data"] as? Map<*, *>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

            val allowedDataKeys = setOf("fields", "additionalProperties")
            for (k in dataMap.keys) {
                if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                if (!allowedDataKeys.contains(k)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
            }
            if (!dataMap.containsKey("additionalProperties") ||
                dataMap["additionalProperties"] != false
            ) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val dataFieldsRaw = dataMap["fields"] as? List<*>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

            val dataFields = ArrayList<ConfigurationFieldDeclaration>()
            for (fieldItem in dataFieldsRaw) {
                val fieldMap = fieldItem as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

                val allowedFieldKeys = setOf("id", "type", "default", "allowedValues", "minimum", "maximum")
                for (k in fieldMap.keys) {
                    if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (!allowedFieldKeys.contains(k)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                }

                val id = fieldMap["id"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val typeStr = fieldMap["type"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                if (!fieldMap.containsKey("default")) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
                val defaultVal = fieldMap["default"]

                val decl = when (typeStr) {
                    "string" -> {
                        if (fieldMap.containsKey("minimum") || fieldMap.containsKey("maximum")) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                        val defaultStr = defaultVal as? String
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        val allowedValues = if (fieldMap.containsKey("allowedValues")) {
                            val avRaw = fieldMap["allowedValues"] as? List<*>
                                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            val avList = ArrayList<String>()
                            for (av in avRaw) {
                                if (av !is String) {
                                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                                }
                                avList.add(av)
                            }
                            avList
                        } else {
                            null
                        }
                        try {
                            ConfigurationFieldDeclaration.StringField(id, defaultStr, allowedValues)
                        } catch (e: Exception) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                    }
                    "boolean" -> {
                        if (fieldMap.containsKey("allowedValues") || fieldMap.containsKey("minimum") || fieldMap.containsKey("maximum")) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                        val defaultBool = defaultVal as? Boolean
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        try {
                            ConfigurationFieldDeclaration.BooleanField(id, defaultBool)
                        } catch (e: Exception) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                    }
                    "integer" -> {
                        if (fieldMap.containsKey("allowedValues")) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                        val defaultLong = asLong(defaultVal)
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

                        val minimum = if (fieldMap.containsKey("minimum")) {
                            asLong(fieldMap["minimum"])
                                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        } else {
                            null
                        }

                        val maximum = if (fieldMap.containsKey("maximum")) {
                            asLong(fieldMap["maximum"])
                                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        } else {
                            null
                        }

                        try {
                            ConfigurationFieldDeclaration.IntegerField(id, defaultLong, minimum, maximum)
                        } catch (e: Exception) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                    }
                    else -> {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                }
                dataFields.add(decl)
            }

            val uiMap = configurationMap["ui"] as? Map<*, *>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            val allowedUiKeys = setOf("fields")
            if (uiMap.keys != allowedUiKeys) {
                val extra = uiMap.keys - allowedUiKeys
                if (extra.isNotEmpty()) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                }
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }
            val uiFieldsRaw = uiMap["fields"] as? List<*>
                ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

            val uiFields = ArrayList<UiFieldDeclaration>()
            for (uiFieldItem in uiFieldsRaw) {
                val uiFieldMap = uiFieldItem as? Map<*, *>
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))

                val controlStr = uiFieldMap["control"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val control = when (controlStr) {
                    "text" -> UiControl.TEXT
                    "toggle" -> UiControl.TOGGLE
                    "number" -> UiControl.NUMBER
                    "choice" -> UiControl.CHOICE
                    "multiline" -> UiControl.MULTILINE
                    "dynamic-choice" -> UiControl.DYNAMIC_CHOICE
                    else -> return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }

                val allowedUiFieldKeys = if (control == UiControl.DYNAMIC_CHOICE) {
                    setOf("field", "control", "label", "help", "source", "dependsOn")
                } else {
                    setOf("field", "control", "label", "help", "choices")
                }
                for (k in uiFieldMap.keys) {
                    if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (!allowedUiFieldKeys.contains(k)) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                    }
                }

                val field = uiFieldMap["field"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val label = uiFieldMap["label"] as? String
                    ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                val help = if (uiFieldMap.containsKey("help")) {
                    val hVal = uiFieldMap["help"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    if (hVal.isBlank()) {
                        return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    }
                    hVal
                } else {
                    null
                }

                val choices = if (uiFieldMap.containsKey("choices")) {
                    val choicesRaw = uiFieldMap["choices"] as? List<*>
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    val choicesList = ArrayList<UiChoice>()
                    for (choiceItem in choicesRaw) {
                        val choiceMap = choiceItem as? Map<*, *>
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        val allowedChoiceKeys = setOf("value", "label")
                        for (k in choiceMap.keys) {
                            if (k !is String) return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                            if (!allowedChoiceKeys.contains(k)) {
                                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.UNKNOWN_FIELDS))
                            }
                        }
                        val cVal = choiceMap["value"] as? String
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        val cLabel = choiceMap["label"] as? String
                            ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        try {
                            choicesList.add(UiChoice(cVal, cLabel))
                        } catch (e: Exception) {
                            return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                        }
                    }
                    choicesList
                } else {
                    null
                }

                val dynamicSource = if (control == UiControl.DYNAMIC_CHOICE) {
                    val sourceStr = uiFieldMap["source"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                    sourceStr
                } else {
                    null
                }
                val dependsOn = if (control == UiControl.DYNAMIC_CHOICE && uiFieldMap.containsKey("dependsOn")) {
                    uiFieldMap["dependsOn"] as? String
                        ?: return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                } else {
                    null
                }

                try {
                    uiFields.add(UiFieldDeclaration(field, control, label, help, choices, dynamicSource, dependsOn))
                } catch (e: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
                }
            }

            val configuration = try {
                PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(dataFields),
                    ConfigurationUiDeclaration(uiFields)
                )
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            val manifest = try {
                PackageManifest(
                    manifestVersion = manifestVersion,
                    repositoryId = sourceRecord.repositoryId,
                    packageVersion = packageVersion,
                    entryModule = entryModule,
                    presentation = PackagePresentation(label, summary),
                    runtime = RuntimeRequirements(luaVersion, apiVersion),
                    configuration = configuration,
                    resources = resources,
                    profileTypes = profileTypes,
                    choiceResolvers = choiceResolvers,
                    workQueues = workQueues,
                    capabilities = capabilities
                )
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MALFORMED_MANIFEST))
            }

            if (!luaSourceMap.containsKey(entryModule)) {
                return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.INVALID_ENTRY_MODULE))
            }

            // Every declared choice resolver module must exist in the immutable
            // source map. Static validation never loads or executes the module.
            for (resolver in manifest.choiceResolvers) {
                if (!luaSourceMap.containsKey(resolver.module)) {
                    return PackageOutcome.Failure(PackageFailure.Format(FormatDetail.MISSING_RESOLVER_MODULE))
                }
            }

            val luaValidationBounds = LuaValidationBounds(
                maxModuleCount = bounds.maxEntryCount,
                maxModuleByteLength = bounds.maxPerModuleBytes,
                maxTotalByteLength = bounds.maxTotalSourceBytes
            )
            val imageResult = ImmutableProgramImage.create(
                entryPoint = entryModule,
                sourceMap = luaSourceMap,
                requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
                bounds = luaValidationBounds
            )

            val programImage = when (imageResult) {
                is ProgramImageCreationResult.Success -> imageResult.image
                is ProgramImageCreationResult.Failure -> {
                    val detail = translateProgramImageError(imageResult.error)
                    return PackageOutcome.Failure(detail)
                }
            }

            val fingerprint = ProviderRevisionFingerprint.fromDigest(digest)
            val revision = ValidatedPackageRevision(
                digest = digest,
                manifest = manifest,
                sourceRecord = sourceRecord,
                sourceMap = luaSourceMap,
                programImage = programImage,
                fingerprint = fingerprint
            )
            return PackageOutcome.Success(revision)
        }
    }

    private fun translateProgramImageError(error: ProgramImageValidationError): PackageFailure = when (error) {
        is ProgramImageValidationError.IncompatibleRequirements -> {
            if (error.requirement == "luaVersion") {
                PackageFailure.Compatibility(CompatibilityDetail.LUA_VERSION_INCOMPATIBLE)
            } else {
                PackageFailure.Compatibility(CompatibilityDetail.API_VERSION_INCOMPATIBLE)
            }
        }
        is ProgramImageValidationError.MissingEntryModule -> {
            PackageFailure.Format(FormatDetail.INVALID_ENTRY_MODULE)
        }
        is ProgramImageValidationError.InvalidModuleName,
        is ProgramImageValidationError.ReservedModuleName -> {
            PackageFailure.Format(FormatDetail.INVALID_MODULE_GRAMMAR)
        }
        is ProgramImageValidationError.MalformedSourceText -> {
            PackageFailure.Format(FormatDetail.INVALID_ZIP)
        }
        is ProgramImageValidationError.BoundsExceeded.TooManyModules,
        is ProgramImageValidationError.BoundsExceeded.ModuleTooLarge,
        is ProgramImageValidationError.BoundsExceeded.TotalSizeTooLarge -> {
            PackageFailure.Format(FormatDetail.BOUNDS_EXCEEDED)
        }
    }

    private fun decompressEntry(
        raf: RandomAccessFile,
        entry: ZipEntryInfo,
        localExtraLength: Int
    ): ByteArray {
        val startOffset = entry.localHeaderOffset + 30 + entry.name.toByteArray(StandardCharsets.UTF_8).size + localExtraLength
        raf.seek(startOffset)

        val bis = BoundedInputStream(raf, entry.compressedSize)
        val decompressedBytes = ByteArray(entry.uncompressedSize.toInt())

        if (entry.method == 0) {
            if (entry.compressedSize != entry.uncompressedSize) {
                throw IOException("Stored entry size mismatch")
            }
            var totalRead = 0
            while (totalRead < decompressedBytes.size) {
                val read = bis.read(decompressedBytes, totalRead, decompressedBytes.size - totalRead)
                if (read == -1) throw IOException("Unexpected end of stored entry stream")
                totalRead += read
            }
            if (bis.remaining != 0L) {
                throw IOException("Stored entry trailing bytes")
            }
            val crc = CRC32()
            crc.update(decompressedBytes)
            if (crc.value != entry.crc) {
                throw IOException("CRC mismatch for stored entry")
            }
        } else {
            val inflater = Inflater(true)
            try {
                val iis = InflaterInputStream(bis, inflater)
                val buffer = ByteArray(4096)
                var totalRead = 0
                val crc = CRC32()
                while (true) {
                    val read = iis.read(buffer)
                    if (read == -1) break
                    if (totalRead + read > decompressedBytes.size) {
                        throw IOException("Decompressed size exceeds expected uncompressed size")
                    }
                    System.arraycopy(buffer, 0, decompressedBytes, totalRead, read)
                    crc.update(buffer, 0, read)
                    totalRead += read
                }
                if (totalRead != decompressedBytes.size) {
                    throw IOException("Decompressed size does not match uncompressed size")
                }
                if (crc.value != entry.crc) {
                    throw IOException("CRC mismatch for deflated entry")
                }
                if (!inflater.finished()) {
                    throw IOException("Inflater did not finish")
                }
                if (bis.remaining != 0L) {
                    throw IOException("Deflated entry trailing bytes")
                }
            } finally {
                inflater.end()
            }
        }
        return decompressedBytes
    }

    private class BoundedInputStream(
        private val raf: RandomAccessFile,
        private val length: Long
    ) : InputStream() {
        var remaining = length
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = raf.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = raf.read(b, off, toRead)
            if (read != -1) remaining -= read
            return read
        }
    }

    private fun asLong(value: Any?): Long? {
        if (value is Number) {
            val doubleVal = value.toDouble()
            val longVal = value.toLong()
            if (doubleVal == longVal.toDouble()) {
                return longVal
            }
        }
        return null
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val charBuffer = decoder.decode(ByteBuffer.wrap(bytes))
        return charBuffer.toString()
    }

    internal class StrictJsonParser(internal val json: String) {
        private var pos = 0

        fun parse(): Map<String, Any?> {
            skipWhitespace()
            if (pos >= json.length || json[pos] != '{') {
                throw IllegalArgumentException("Expected '{' at start of JSON object")
            }
            val obj = parseObject()
            skipWhitespace()
            if (pos < json.length) {
                throw IllegalArgumentException("Extra characters after JSON root object")
            }
            return obj
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // skip '{'
            skipWhitespace()
            if (pos < json.length && json[pos] == '}') {
                pos++
                return map
            }
            while (pos < json.length) {
                skipWhitespace()
                if (pos >= json.length || json[pos] != '"') {
                    throw IllegalArgumentException("Expected string key")
                }
                val key = parseString()
                skipWhitespace()
                if (pos >= json.length || json[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' after key '$key'")
                }
                pos++ // skip ':'
                val value = parseValue()
                if (map.containsKey(key)) {
                    throw DuplicateKeyException("Duplicate key '$key' detected")
                }
                map[key] = value
                skipWhitespace()
                if (pos < json.length && json[pos] == ',') {
                    pos++
                    skipWhitespace()
                    if (pos < json.length && json[pos] == '}') {
                        throw IllegalArgumentException("Trailing comma prohibited")
                    }
                } else if (pos < json.length && json[pos] == '}') {
                    pos++
                    return map
                } else {
                    throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
            throw IllegalArgumentException("Unterminated JSON object")
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= json.length) throw IllegalArgumentException("Unexpected EOF")
            return when (val c = json[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> {
                    if (c == '-' || c.isDigit()) {
                        parseNumber()
                    } else {
                        throw IllegalArgumentException("Unexpected character '$c'")
                    }
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // skip '['
            skipWhitespace()
            if (pos < json.length && json[pos] == ']') {
                pos++
                return list
            }
            while (pos < json.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < json.length && json[pos] == ',') {
                    pos++
                    skipWhitespace()
                    if (pos < json.length && json[pos] == ']') {
                        throw IllegalArgumentException("Trailing comma prohibited")
                    }
                } else if (pos < json.length && json[pos] == ']') {
                    pos++
                    return list
                } else {
                    throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
            throw IllegalArgumentException("Unterminated JSON array")
        }

        private fun parseString(): String {
            pos++ // skip '"'
            val sb = java.lang.StringBuilder()
            while (pos < json.length) {
                val c = json[pos]
                if (c == '"') {
                    pos++
                    return sb.toString()
                } else if (c == '\\') {
                    if (pos + 1 >= json.length) throw IllegalArgumentException("Unterminated escape sequence")
                    val next = json[pos + 1]
                    pos += 2
                    when (next) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > json.length) throw IllegalArgumentException("Invalid unicode escape")
                            val hex = json.substring(pos, pos + 4)
                            pos += 4
                            val code = hex.toInt(16)
                            val charVal = code.toChar()
                            if (charVal.isHighSurrogate()) {
                                if (pos + 6 > json.length || json[pos] != '\\' || json[pos + 1] != 'u') {
                                    throw IllegalArgumentException("Unpaired high surrogate")
                                }
                                pos += 2
                                val hexLow = json.substring(pos, pos + 4)
                                pos += 4
                                val codeLow = hexLow.toInt(16)
                                val charLow = codeLow.toChar()
                                if (!charLow.isLowSurrogate()) {
                                    throw IllegalArgumentException("Expected low surrogate after high surrogate")
                                }
                                sb.append(charVal)
                                sb.append(charLow)
                            } else if (charVal.isLowSurrogate()) {
                                throw IllegalArgumentException("Unpaired low surrogate")
                            } else {
                                sb.append(charVal)
                            }
                        }
                        else -> throw IllegalArgumentException("Invalid escape character '$next'")
                    }
                } else {
                    if (c.code < 0x20) {
                        throw IllegalArgumentException("Raw control characters are prohibited in JSON strings")
                    }
                    sb.append(c)
                    pos++
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseBoolean(): Boolean {
            if (json.startsWith("true", pos)) {
                pos += 4
                return true
            } else if (json.startsWith("false", pos)) {
                pos += 5
                return false
            }
            throw IllegalArgumentException("Invalid boolean")
        }

        private fun parseNull(): Any? {
            if (json.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (json[pos] == '-') pos++
            if (pos >= json.length) throw IllegalArgumentException("Invalid number")
            val firstDigit = json[pos]
            if (!firstDigit.isDigit()) throw IllegalArgumentException("Expected digit")
            if (firstDigit == '0') {
                pos++
                if (pos < json.length && json[pos].isDigit()) {
                    throw IllegalArgumentException("Leading zeros are prohibited")
                }
            } else {
                while (pos < json.length && json[pos].isDigit()) pos++
            }
            var isDecimal = false
            if (pos < json.length && json[pos] == '.') {
                isDecimal = true
                pos++
                if (pos >= json.length || !json[pos].isDigit()) {
                    throw IllegalArgumentException("Expected digit after decimal point")
                }
                while (pos < json.length && json[pos].isDigit()) pos++
            }
            if (pos < json.length && (json[pos] == 'e' || json[pos] == 'E')) {
                isDecimal = true
                pos++
                if (pos < json.length && (json[pos] == '+' || json[pos] == '-')) pos++
                if (pos >= json.length || !json[pos].isDigit()) {
                    throw IllegalArgumentException("Expected digit in exponent")
                }
                while (pos < json.length && json[pos].isDigit()) pos++
            }
            val numStr = json.substring(start, pos)
            return if (isDecimal) {
                numStr.toDouble()
            } else {
                val longVal = numStr.toLong()
                if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) {
                    longVal.toInt()
                } else {
                    longVal
                }
            }
        }

        private fun skipWhitespace() {
            while (pos < json.length && (json[pos] == ' ' || json[pos] == '\t' || json[pos] == '\r' || json[pos] == '\n')) {
                pos++
            }
        }

        private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'
        private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'
    }
}
