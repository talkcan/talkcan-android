package io.talkcan.mount.saf

import io.talkcan.resource.MountBindingStatus

/**
 * 3.4: Pure mapping from SAF grant facts to the portable status vocabulary.
 *
 * Inputs are platform facts already normalized by [SafGrantController]; the
 * output is the shared [MountBindingStatus] enum with exactly `available`,
 * `read-only`, `needs-reauthorization`, and `unavailable`. No Android type
 * crosses this function's boundary.
 */
object SafGrantStatusMapper {

    /**
     * Maps live grant facts for one stored binding to its portable status.
     *
     * - The platform grant vanished (revoked or never restored) while a
     *   binding still exists → `needs-reauthorization`.
     * - The provider is unreachable or the root moved/deleted → `unavailable`.
     * - The persisted entry lost the requested read grant → `needs-reauthorization`.
     * - Read works but the requested write grant or directory-create support
     *   is missing → `read-only`.
     * - Otherwise → `available`.
     */
    fun map(
        requested: SafRequestedAccess,
        persisted: SafPersistedGrant?,
        probe: SafTreeProbe?,
    ): MountBindingStatus {
        if (persisted == null) return MountBindingStatus.NEEDS_REAUTHORIZATION
        val reachable = probe as? SafTreeProbe.Reachable ?: return MountBindingStatus.UNAVAILABLE
        if (requested.read && !persisted.readPermission) {
            return MountBindingStatus.NEEDS_REAUTHORIZATION
        }
        if (requested.write && (!persisted.writePermission || !reachable.directoryCreateSupported)) {
            return MountBindingStatus.READ_ONLY
        }
        return MountBindingStatus.AVAILABLE
    }
}
