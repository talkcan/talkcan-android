package io.talkcan.model

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface ChannelCatalogueFileStoreResult {
    data object Success : ChannelCatalogueFileStoreResult
    data class Failure(val operation: String, val cause: IOException) : ChannelCatalogueFileStoreResult
}

sealed interface ChannelCatalogueLoadResult {
    data class Success(val document: DecodedChannelCatalogue) : ChannelCatalogueLoadResult
    data class Failure(val error: ChannelCatalogueDecodeError) : ChannelCatalogueLoadResult
}

class ChannelCatalogueFileStore(
    private val file: File,
) {
    fun load(): ChannelCatalogueLoadResult? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            when (val decoded = ChannelCatalogueCodec.decode(file.readText())) {
                is ChannelCatalogueDecodeResult.Success -> ChannelCatalogueLoadResult.Success(decoded.document)
                is ChannelCatalogueDecodeResult.Failure -> ChannelCatalogueLoadResult.Failure(decoded.error)
            }
        } catch (error: IOException) {
            ChannelCatalogueLoadResult.Failure(
                ChannelCatalogueDecodeError.MalformedDocument(error.message ?: "Unable to read catalogue"),
            )
        }
    }

    /** Preserves the exact v1 document once, before atomically replacing it with v2. */
    @Synchronized
    fun backupLegacyV1(): ChannelCatalogueFileStoreResult {
        val parent = file.absoluteFile.parentFile
        val backup = File(parent, "${file.name}.v1.bak")
        if (backup.exists()) return ChannelCatalogueFileStoreResult.Success
        return try {
            parent.mkdirs()
            val temporaryBackup = File(parent, "${backup.name}.tmp")
            Files.copy(file.toPath(), temporaryBackup.toPath())
            Files.move(
                temporaryBackup.toPath(),
                backup.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            ChannelCatalogueFileStoreResult.Success
        } catch (error: IOException) {
            ChannelCatalogueFileStoreResult.Failure("backup v1 catalogue", error)
        }
    }

    @Synchronized
    fun save(snapshot: ChannelCatalogueSnapshot): ChannelCatalogueFileStoreResult {
        if (ChannelCatalogueValidator.validate(snapshot) !is ChannelCatalogueValidationResult.Valid) {
            return ChannelCatalogueFileStoreResult.Failure(
                "validate catalogue",
                IOException("Refusing to persist an invalid catalogue"),
            )
        }
        val parent = file.absoluteFile.parentFile
        val temporary = File(parent, "${file.name}.tmp")
        return try {
            parent.mkdirs()
            temporary.writeText(ChannelCatalogueCodec.toJson(snapshot))
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            ChannelCatalogueFileStoreResult.Success
        } catch (error: IOException) {
            if (temporary.exists()) temporary.delete()
            ChannelCatalogueFileStoreResult.Failure("save catalogue", error)
        }
    }
}
