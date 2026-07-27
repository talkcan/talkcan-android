package io.talkcan.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.8: Reverse dependency index contract tests. */
class ProfileDependencyIndexTest {

    private val p1 = ProfileId("p1")
    private val p2 = ProfileId("p2")

    @Test
    fun registersAndQueriesDependents() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        index.registerDependency(p1, "instance-b")
        index.registerDependency(p2, "instance-b")

        assertEquals(setOf("instance-a", "instance-b"), index.dependentsOf(p1))
        assertEquals(setOf("instance-b"), index.dependentsOf(p2))
        assertEquals(setOf(p1, p2), index.profilesForInstance("instance-b"))
    }

    @Test
    fun affectedInstancesIsUnionOfSelectedProfiles() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        index.registerDependency(p2, "instance-b")
        index.registerDependency(p2, "instance-c")

        assertEquals(setOf("instance-a", "instance-b", "instance-c"), index.affectedInstances(listOf(p1, p2)))
        assertEquals(setOf("instance-a"), index.affectedInstances(listOf(p1)))
    }

    @Test
    fun unrelatedProfileChangeAffectsNoInstance() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        // p2 has no dependents: editing it must not replace any generation.
        assertTrue(index.affectedInstances(listOf(p2)).isEmpty())
    }

    @Test
    fun unregisterInstanceRemovesAllItsEdges() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        index.registerDependency(p2, "instance-a")
        index.unregisterInstance("instance-a")
        assertTrue(index.dependentsOf(p1).isEmpty())
        assertTrue(index.dependentsOf(p2).isEmpty())
        assertTrue(index.profilesForInstance("instance-a").isEmpty())
    }

    @Test
    fun unregisterProfileReturnsAffectedInstances() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        index.registerDependency(p1, "instance-b")
        val affected = index.unregisterProfile(p1)
        assertEquals(setOf("instance-a", "instance-b"), affected)
        assertTrue(index.dependentsOf(p1).isEmpty())
    }

    @Test
    fun unregisterSingleDependencyKeepsOthers() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        index.registerDependency(p1, "instance-b")
        index.unregisterDependency(p1, "instance-a")
        assertEquals(setOf("instance-b"), index.dependentsOf(p1))
        assertTrue(index.profilesForInstance("instance-a").isEmpty())
    }

    @Test
    fun duplicateRegistrationIsIdempotent() {
        val index = ProfileDependencyIndex()
        index.registerDependency(p1, "instance-a")
        index.registerDependency(p1, "instance-a")
        assertEquals(setOf("instance-a"), index.dependentsOf(p1))
        assertEquals(1, index.snapshot().size)
    }
}
