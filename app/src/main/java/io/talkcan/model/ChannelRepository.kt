package io.talkcan.model

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ChannelRepositoryError {
    val message: String

    data class Decode(val error: ChannelCatalogueDecodeError) : ChannelRepositoryError {
        override val message = error.message
    }

    data class ProviderMigration(
        val definitionId: String,
        val error: ChannelProviderError,
    ) : ChannelRepositoryError {
        override val message = "Could not migrate channel $definitionId: ${error.message}"
    }

    data class Storage(val operation: String, val cause: IOException) : ChannelRepositoryError {
        override val message = "Could not $operation: ${cause.message}"
    }

    data class Mutation(val error: ChannelCatalogueError) : ChannelRepositoryError {
        override val message = error.message
    }
}

sealed interface ChannelRepositoryMutationResult {
    data object Success : ChannelRepositoryMutationResult
    data class Failure(val error: ChannelRepositoryError) : ChannelRepositoryMutationResult
}

class ChannelRepositoryLoadException(val error: ChannelRepositoryError) : IllegalStateException(error.message)

class ChannelRepository(
    private val prefs: SharedPreferences,
    private val catalogueFile: File,
    private val descriptorResolver: ChannelImplementationDescriptorResolver,
) {
    constructor(
        context: Context,
        descriptorResolver: ChannelImplementationDescriptorResolver,
    ) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        File(context.filesDir, "channels_catalogue.json"),
        descriptorResolver,
    )

    constructor(
        prefs: SharedPreferences,
        descriptorResolver: ChannelImplementationDescriptorResolver,
    ) : this(
        prefs,
        File(System.getProperty("java.io.tmpdir"), "channels_catalogue_${System.nanoTime()}.json")
            .apply { deleteOnExit() },
        descriptorResolver,
    )

    private val fileStore = ChannelCatalogueFileStore(catalogueFile)
    private val _catalogueState: MutableStateFlow<ChannelCatalogueSnapshot>
    private val mutationLock = Any()

    val catalogueState: StateFlow<ChannelCatalogueSnapshot>
        get() = _catalogueState.asStateFlow()

    init {
        val initial = when (val loaded = fileStore.load()) {
            null -> seedEmptyCatalogue().also(::saveOrThrow)
            is ChannelCatalogueLoadResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.Decode(loaded.error),
            )
            is ChannelCatalogueLoadResult.Success -> migrateLoadedDocument(loaded.document)
        }
        _catalogueState = MutableStateFlow(initial)
    }

    private fun migrateLoadedDocument(document: DecodedChannelCatalogue): ChannelCatalogueSnapshot {
        val migration = ChannelCatalogueProviderMigrator.migrate(document.snapshot, descriptorResolver)
        val migrated = when (migration) {
            is ChannelCatalogueProviderMigrationResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.ProviderMigration(migration.definitionId, migration.error),
            )
            is ChannelCatalogueProviderMigrationResult.Success -> migration
        }
        if (document.sourceDocumentVersion == 1) {
            backupLegacyV1OrThrow()
            saveOrThrow(migrated.snapshot)
        } else if (document.sourceDocumentVersion < ChannelCatalogueCodec.CURRENT_DOCUMENT_VERSION || migrated.changed) {
            saveOrThrow(migrated.snapshot)
        }
        return migrated.snapshot
    }

    private fun seedEmptyCatalogue(): ChannelCatalogueSnapshot =
        ChannelCatalogueSnapshot(definitions = emptyList(), activeChannelId = null)

    fun selectChannel(id: String): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.selectChannel(id))
    }

    /**
     * Atomically creates a channel. The definition's host preferences are committed together with
     * the provider-owned configuration in one catalogue transaction; provider migration applies to
     * the opaque configuration only and never touches host preferences.
     */
    fun addChannel(definition: ChannelDefinition): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        when (val migrated = migrateProviderDefinition(definition)) {
            is ChannelDefinitionMigrationResult.Failure -> ChannelRepositoryMutationResult.Failure(migrated.error)
            is ChannelDefinitionMigrationResult.Success -> commit(_catalogueState.value.addChannel(migrated.definition))
        }
    }

    fun updateChannel(
        id: String,
        transform: (ChannelDefinition) -> ChannelDefinition,
    ): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        val current = _catalogueState.value
        val currentDefinition = current.definitions.find { it.id == id }
            ?: return@synchronized ChannelRepositoryMutationResult.Failure(
                ChannelRepositoryError.Mutation(ChannelCatalogueError.UnknownChannelId(id)),
            )
        val replacement = transform(currentDefinition)
        if (replacement.id != id) return@synchronized commit(current.updateChannel(id) { replacement })
        when (val migrated = migrateProviderDefinition(replacement)) {
            is ChannelDefinitionMigrationResult.Failure -> ChannelRepositoryMutationResult.Failure(migrated.error)
            is ChannelDefinitionMigrationResult.Success -> commit(current.updateChannel(id) { migrated.definition })
        }
    }

    /**
     * Atomically replaces only the host-owned preferences of a channel. This bypasses provider
     * configuration migration and validation entirely and never replaces the channel's runtime
     * generation: the opaque provider payload and schema version are preserved byte-for-byte.
     */
    fun updateChannelHostPreferences(
        id: String,
        preferences: ChannelHostPreferences,
    ): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.updateChannel(id) { it.copy(hostPreferences = preferences) })
    }

    fun moveChannel(id: String, toIndex: Int): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.moveChannel(id, toIndex))
    }

    fun removeChannel(id: String): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.removeChannel(id))
    }

    private sealed interface ChannelDefinitionMigrationResult {
        data class Success(val definition: ChannelDefinition) : ChannelDefinitionMigrationResult
        data class Failure(val error: ChannelRepositoryError) : ChannelDefinitionMigrationResult
    }

    private fun migrateProviderDefinition(definition: ChannelDefinition): ChannelDefinitionMigrationResult = when (
        val resolution = descriptorResolver.resolveDescriptor(definition.implementationId)
    ) {
        is ChannelDescriptorResolution.Missing -> ChannelDefinitionMigrationResult.Failure(
            ChannelRepositoryError.ProviderMigration(definition.id, resolution.error),
        )
        is ChannelDescriptorResolution.Available -> when (
            val result = resolution.descriptor.configuration.migrateAndValidate(
                definition.configSchemaVersion,
                definition.configPayload,
            )
        ) {
            is ProviderConfigurationResult.Failure -> ChannelDefinitionMigrationResult.Failure(
                ChannelRepositoryError.ProviderMigration(definition.id, result.error),
            )
            is ProviderConfigurationResult.Success -> ChannelDefinitionMigrationResult.Success(
                definition.copy(
                    configSchemaVersion = result.configuration.schemaVersion,
                    configPayload = result.configuration.payload,
                ),
            )
        }
    }

    private fun commit(result: ChannelCatalogueMutationResult): ChannelRepositoryMutationResult = when (result) {
        is ChannelCatalogueMutationResult.Failure ->
            ChannelRepositoryMutationResult.Failure(ChannelRepositoryError.Mutation(result.error))
        is ChannelCatalogueMutationResult.Success -> {
            when (val stored = fileStore.save(result.snapshot)) {
                ChannelCatalogueFileStoreResult.Success -> {
                    _catalogueState.value = result.snapshot
                    ChannelRepositoryMutationResult.Success
                }
                is ChannelCatalogueFileStoreResult.Failure -> ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.Storage(stored.operation, stored.cause),
                )
            }
        }
    }

    private fun saveOrThrow(snapshot: ChannelCatalogueSnapshot) {
        when (val stored = fileStore.save(snapshot)) {
            ChannelCatalogueFileStoreResult.Success -> Unit
            is ChannelCatalogueFileStoreResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.Storage(stored.operation, stored.cause),
            )
        }
    }

    private fun backupLegacyV1OrThrow() {
        when (val backup = fileStore.backupLegacyV1()) {
            ChannelCatalogueFileStoreResult.Success -> Unit
            is ChannelCatalogueFileStoreResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.Storage(backup.operation, backup.cause),
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "channels"
    }
}
