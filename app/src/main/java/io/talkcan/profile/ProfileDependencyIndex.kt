package io.talkcan.profile

/**
 * 3.8: Reverse dependency index from selected profile IDs to channel
 * instances.
 *
 * Channel configurations persist only stable profile IDs. This index tracks
 * which channel instances currently select which profiles so that a committed
 * profile mutation can invalidate exactly the affected resolver requests,
 * secret references, and runtime generations — and nothing else. The index is
 * a pure data structure: it does not perform invalidation itself. The
 * coordinator queries [affectedInstances] after a committed mutation and
 * drives reconciliation through the runtime registry and resolver
 * orchestrator.
 *
 * Creating an unrelated profile (no dependents) yields an empty invalidation
 * set, so unrelated channel generations remain live.
 */
public class ProfileDependencyIndex {
    private val lock = Any()
    private val dependents: LinkedHashMap<ProfileId, MutableSet<String>> = LinkedHashMap()
    private val instanceProfiles: LinkedHashMap<String, MutableSet<ProfileId>> = LinkedHashMap()

    /** Record that [channelInstanceId] currently selects [profileId]. */
    public fun registerDependency(profileId: ProfileId, channelInstanceId: String): Unit = synchronized(lock) {
        dependents.getOrPut(profileId) { LinkedHashSet() }.add(channelInstanceId)
        instanceProfiles.getOrPut(channelInstanceId) { LinkedHashSet() }.add(profileId)
    }

    /** Drop one selection edge (e.g., a channel reselected another profile). */
    public fun unregisterDependency(profileId: ProfileId, channelInstanceId: String): Unit = synchronized(lock) {
        dependents[profileId]?.remove(channelInstanceId)
        if (dependents[profileId]?.isEmpty() == true) dependents.remove(profileId)
        instanceProfiles[channelInstanceId]?.remove(profileId)
        if (instanceProfiles[channelInstanceId]?.isEmpty() == true) instanceProfiles.remove(channelInstanceId)
    }

    /** Drop every selection made by one channel instance (instance deletion). */
    public fun unregisterInstance(channelInstanceId: String): Unit = synchronized(lock) {
        val selected = instanceProfiles.remove(channelInstanceId) ?: return
        for (profileId in selected) {
            dependents[profileId]?.remove(channelInstanceId)
            if (dependents[profileId]?.isEmpty() == true) dependents.remove(profileId)
        }
    }

    /** Drop every dependent edge for a profile (profile deletion). */
    public fun unregisterProfile(profileId: ProfileId): Set<String> = synchronized(lock) {
        val affected = dependents.remove(profileId) ?: return emptySet()
        val snapshot = affected.toSortedSet()
        for (channelInstanceId in affected) {
            instanceProfiles[channelInstanceId]?.remove(profileId)
            if (instanceProfiles[channelInstanceId]?.isEmpty() == true) instanceProfiles.remove(channelInstanceId)
        }
        snapshot
    }

    /** Channel instances currently selecting [profileId], sorted. */
    public fun dependentsOf(profileId: ProfileId): Set<String> = synchronized(lock) {
        dependents[profileId]?.toSortedSet() ?: emptySet()
    }

    /** Profile IDs currently selected by [channelInstanceId], sorted by ID. */
    public fun profilesForInstance(channelInstanceId: String): Set<ProfileId> = synchronized(lock) {
        instanceProfiles[channelInstanceId]
            ?.sortedBy { it.value }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * 3.8: Commit-triggered invalidation set. Given the profile IDs touched by
     * a committed mutation, return the union of channel instances that select
     * any of them. The coordinator invalidates exactly these instances'
     * resolver requests, secret references, and runtime generations. An empty
     * result means no generation need be replaced.
     */
    public fun affectedInstances(mutatedProfileIds: Collection<ProfileId>): Set<String> = synchronized(lock) {
        val affected = sortedSetOf<String>()
        for (profileId in mutatedProfileIds) {
            dependents[profileId]?.let { affected.addAll(it) }
        }
        affected
    }

    /** Deterministic snapshot of every dependency edge. */
    public fun snapshot(): List<Pair<ProfileId, String>> = synchronized(lock) {
        val edges = ArrayList<Pair<ProfileId, String>>()
        for ((profileId, instances) in dependents) {
            for (instance in instances.toSortedSet()) {
                edges.add(profileId to instance)
            }
        }
        edges.sortedWith(compareBy({ it.first.value }, { it.second }))
    }
}
