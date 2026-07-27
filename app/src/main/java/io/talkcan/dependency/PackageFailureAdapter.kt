package io.talkcan.dependency

import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelProviderError

/**
 * Exhaustive adapter from the dependency-layer [PackageFailure] sealed hierarchy to the
 * model-layer [ChannelProviderError.PackageUnavailable] typed error.
 *
 * The model layer deliberately does not import [PackageFailure]; instead it exposes a
 * normalized category + detail enum pair. This adapter is the sole mapping site: adding a
 * new [PackageFailure] subtype or detail requires a matching branch here, enforced at
 * compile time by the sealed `when` expressions.
 */
internal data class PackageUnavailableProjection(
    val category: ChannelProviderError.PackageUnavailableCategory,
    val detail: ChannelProviderError.PackageUnavailableDetail,
)

internal fun PackageFailure.toPackageUnavailableProjection(): PackageUnavailableProjection {
    val category = when (this.category) {
        PackageFailure.FailureCategory.FORMAT ->
            ChannelProviderError.PackageUnavailableCategory.FORMAT
        PackageFailure.FailureCategory.IDENTITY ->
            ChannelProviderError.PackageUnavailableCategory.IDENTITY
        PackageFailure.FailureCategory.COMPATIBILITY ->
            ChannelProviderError.PackageUnavailableCategory.COMPATIBILITY
        PackageFailure.FailureCategory.INTEGRITY ->
            ChannelProviderError.PackageUnavailableCategory.INTEGRITY
        PackageFailure.FailureCategory.STORAGE ->
            ChannelProviderError.PackageUnavailableCategory.STORAGE
        PackageFailure.FailureCategory.RECOVERY ->
            ChannelProviderError.PackageUnavailableCategory.RECOVERY
        PackageFailure.FailureCategory.MUTATION ->
            ChannelProviderError.PackageUnavailableCategory.MUTATION
        PackageFailure.FailureCategory.ROLLBACK ->
            ChannelProviderError.PackageUnavailableCategory.ROLLBACK
        PackageFailure.FailureCategory.LOADING ->
            ChannelProviderError.PackageUnavailableCategory.LOADING
        PackageFailure.FailureCategory.SHUTDOWN ->
            ChannelProviderError.PackageUnavailableCategory.SHUTDOWN
        PackageFailure.FailureCategory.CAPABILITY ->
            ChannelProviderError.PackageUnavailableCategory.FORMAT
    }

    val detail = when (this) {
        is PackageFailure.Format -> when (this.detail) {
            PackageFailure.FormatDetail.INVALID_ZIP ->
                ChannelProviderError.PackageUnavailableDetail.INVALID_ZIP
            PackageFailure.FormatDetail.UNEXPECTED_ENTRY ->
                ChannelProviderError.PackageUnavailableDetail.UNEXPECTED_ENTRY
            PackageFailure.FormatDetail.MISSING_MANIFEST ->
                ChannelProviderError.PackageUnavailableDetail.MISSING_MANIFEST
            PackageFailure.FormatDetail.MALFORMED_MANIFEST ->
                ChannelProviderError.PackageUnavailableDetail.MALFORMED_MANIFEST
            PackageFailure.FormatDetail.DUPLICATE_KEYS ->
                ChannelProviderError.PackageUnavailableDetail.DUPLICATE_KEYS
            PackageFailure.FormatDetail.UNKNOWN_FIELDS ->
                ChannelProviderError.PackageUnavailableDetail.UNKNOWN_FIELDS
            PackageFailure.FormatDetail.INVALID_ENTRY_MODULE ->
                ChannelProviderError.PackageUnavailableDetail.INVALID_ENTRY_MODULE
            PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR ->
                ChannelProviderError.PackageUnavailableDetail.INVALID_MODULE_GRAMMAR
            PackageFailure.FormatDetail.COLLISION ->
                ChannelProviderError.PackageUnavailableDetail.COLLISION
            PackageFailure.FormatDetail.BYTECODE_PROHIBITED ->
                ChannelProviderError.PackageUnavailableDetail.BYTECODE_PROHIBITED
            PackageFailure.FormatDetail.UNSUPPORTED_COMPRESSION ->
                ChannelProviderError.PackageUnavailableDetail.UNSUPPORTED_COMPRESSION
            PackageFailure.FormatDetail.ENCRYPTED_ENTRY ->
                ChannelProviderError.PackageUnavailableDetail.ENCRYPTED_ENTRY
            PackageFailure.FormatDetail.BOUNDS_EXCEEDED ->
                ChannelProviderError.PackageUnavailableDetail.BOUNDS_EXCEEDED
            PackageFailure.FormatDetail.MISSING_RESOLVER_MODULE ->
                ChannelProviderError.PackageUnavailableDetail.MISSING_RESOLVER_MODULE
        }
        is PackageFailure.Identity -> when (this.detail) {
            PackageFailure.IdentityDetail.REPOSITORY_ID_MISMATCH ->
                ChannelProviderError.PackageUnavailableDetail.REPOSITORY_ID_MISMATCH
            PackageFailure.IdentityDetail.RESERVED_NAMESPACE_CLAIM ->
                ChannelProviderError.PackageUnavailableDetail.RESERVED_NAMESPACE_CLAIM
        }
        is PackageFailure.Compatibility -> when (this.detail) {
            PackageFailure.CompatibilityDetail.UNSUPPORTED_MANIFEST_VERSION ->
                ChannelProviderError.PackageUnavailableDetail.UNSUPPORTED_MANIFEST_VERSION
            PackageFailure.CompatibilityDetail.LUA_VERSION_INCOMPATIBLE ->
                ChannelProviderError.PackageUnavailableDetail.LUA_VERSION_INCOMPATIBLE
            PackageFailure.CompatibilityDetail.API_VERSION_INCOMPATIBLE ->
                ChannelProviderError.PackageUnavailableDetail.API_VERSION_INCOMPATIBLE
        }
        is PackageFailure.Integrity -> when (this.detail) {
            PackageFailure.IntegrityDetail.DIGEST_MISMATCH ->
                ChannelProviderError.PackageUnavailableDetail.DIGEST_MISMATCH
            PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE ->
                ChannelProviderError.PackageUnavailableDetail.CORRUPTED_ARCHIVE
            PackageFailure.IntegrityDetail.HASH_COMPUTATION_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.HASH_COMPUTATION_FAILED
        }
        is PackageFailure.Storage -> when (this.detail) {
            PackageFailure.StorageDetail.WRITE_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.WRITE_FAILED
            PackageFailure.StorageDetail.COMMIT_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.COMMIT_FAILED
            PackageFailure.StorageDetail.INSUFFICIENT_SPACE ->
                ChannelProviderError.PackageUnavailableDetail.INSUFFICIENT_SPACE
        }
        is PackageFailure.Recovery -> when (this.detail) {
            PackageFailure.RecoveryDetail.INDEX_CORRUPT ->
                ChannelProviderError.PackageUnavailableDetail.INDEX_CORRUPT
            PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID ->
                ChannelProviderError.PackageUnavailableDetail.RECOVERY_INDEX_INVALID
            PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.ORPHAN_CLEANUP_FAILED
            PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS ->
                ChannelProviderError.PackageUnavailableDetail.COMMIT_STATE_AMBIGUOUS
        }
        is PackageFailure.Mutation -> when (this.detail) {
            PackageFailure.MutationDetail.SERIALIZATION_VIOLATION ->
                ChannelProviderError.PackageUnavailableDetail.SERIALIZATION_VIOLATION
            PackageFailure.MutationDetail.CONCURRENT_MUTATION ->
                ChannelProviderError.PackageUnavailableDetail.CONCURRENT_MUTATION
            PackageFailure.MutationDetail.STAGE_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.STAGE_FAILED
            PackageFailure.MutationDetail.NOT_INSTALLED ->
                ChannelProviderError.PackageUnavailableDetail.NOT_INSTALLED
        }
        is PackageFailure.Rollback -> when (this.detail) {
            PackageFailure.RollbackDetail.NO_ROLLBACK_REVISION ->
                ChannelProviderError.PackageUnavailableDetail.NO_ROLLBACK_REVISION
            PackageFailure.RollbackDetail.ROLLBACK_VALIDATION_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.ROLLBACK_VALIDATION_FAILED
        }
        is PackageFailure.Loading -> when (this.detail) {
            PackageFailure.LoadingDetail.LOAD_CANCELLED ->
                ChannelProviderError.PackageUnavailableDetail.LOAD_CANCELLED
            PackageFailure.LoadingDetail.STALE_PUBLICATION ->
                ChannelProviderError.PackageUnavailableDetail.STALE_PUBLICATION
            PackageFailure.LoadingDetail.LOAD_TIMEOUT ->
                ChannelProviderError.PackageUnavailableDetail.LOAD_TIMEOUT
            PackageFailure.LoadingDetail.RECONCILIATION_FAILED ->
                ChannelProviderError.PackageUnavailableDetail.RECONCILIATION_FAILED
            PackageFailure.LoadingDetail.PUBLICATION_REJECTED ->
                ChannelProviderError.PackageUnavailableDetail.PUBLICATION_REJECTED
        }
        is PackageFailure.Shutdown -> when (this.detail) {
            PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS ->
                ChannelProviderError.PackageUnavailableDetail.SHUTDOWN_IN_PROGRESS
            PackageFailure.ShutdownDetail.TRANSACTION_ABORTED ->
                ChannelProviderError.PackageUnavailableDetail.TRANSACTION_ABORTED
        }
        is PackageFailure.Capability ->
            ChannelProviderError.PackageUnavailableDetail.MALFORMED_MANIFEST
    }

    return PackageUnavailableProjection(category, detail)
}

internal fun PackageFailure.toPackageUnavailable(
    implementationId: ChannelImplementationId,
): ChannelProviderError.PackageUnavailable {
    val projection = toPackageUnavailableProjection()
    return ChannelProviderError.PackageUnavailable(
        implementationId,
        projection.category,
        projection.detail,
    )
}
