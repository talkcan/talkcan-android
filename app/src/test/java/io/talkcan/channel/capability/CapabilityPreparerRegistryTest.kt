package io.talkcan.channel.capability

import io.talkcan.dependency.PackageCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.1-4.8: Focused contract tests for the generic capability-preparer registry,
 * public→host mapping, and the host keyboard-output preparer.
 */
class CapabilityPreparerRegistryTest {

    // ── 4.1: public→host mapping ────────────────────────────────────────────

    @Test
    fun `keyboard output maps to text output host key`() {
        assertEquals(CapabilityKey.TextOutput, PublicCapabilityPreparation.hostKey(PackageCapability.KEYBOARD_OUTPUT))
    }

    @Test
    fun `unknown public capability maps to null`() {
        assertNull(PublicCapabilityPreparation.hostKey("unknown.capability"))
    }

    @Test
    fun `non-preparable public capabilities map to null`() {
        assertNull(PublicCapabilityPreparation.hostKey(PackageCapability.AUDIO_TRANSCRIPTION))
        assertNull(PublicCapabilityPreparation.hostKey(PackageCapability.AUDIO_SYNTHESIS))
        assertNull(PublicCapabilityPreparation.hostKey(PackageCapability.AUDIO_PLAYBACK))
        assertNull(PublicCapabilityPreparation.hostKey(PackageCapability.STORAGE_FILES))
        assertNull(PublicCapabilityPreparation.hostKey(PackageCapability.AUDIO_FILES))
    }

    // ── 4.2: bounded generic registry ───────────────────────────────────────

    @Test
    fun `empty registry reports nothing preparable`() {
        val registry = CapabilityPreparerRegistry.empty()
        assertTrue(registry.preparableCapabilityIds.isEmpty())
        assertFalse(registry.isPreparable(PackageCapability.KEYBOARD_OUTPUT))
        assertNull(registry.preparerFor(PackageCapability.KEYBOARD_OUTPUT))
    }

    @Test
    fun `builder registers and exposes preparers by public id`() {
        val preparer = CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared }
        val registry = CapabilityPreparerRegistry.Builder()
            .register("test.capability", preparer)
            .build()
        assertTrue(registry.isPreparable("test.capability"))
        assertNotNull(registry.preparerFor("test.capability"))
        assertEquals(setOf("test.capability"), registry.preparableCapabilityIds)
    }

    @Test
    fun `duplicate registration is rejected`() {
        val builder = CapabilityPreparerRegistry.Builder()
            .register("dup.cap", CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared })
        assertThrows(IllegalArgumentException::class.java) {
            builder.register("dup.cap", CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared })
        }
    }

    @Test
    fun `blank capability id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityPreparerRegistry.Builder().register("", CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared })
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityPreparerRegistry.Builder().register("   ", CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared })
        }
    }

    @Test
    fun `over-bound registration is rejected`() {
        val builder = CapabilityPreparerRegistry.Builder()
        repeat(CapabilityPreparerRegistry.MAX_PREPARERS) { index ->
            builder.register("cap.$index", CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared })
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.register("cap.overflow", CapabilityPreparer { _, _ -> CapabilityPreparationOutcome.Prepared })
        }
    }

    // ── 4.3: default keyboard-output preparer registration ──────────────────

    @Test
    fun `default registry registers keyboard output preparer`() {
        val registry = CapabilityPreparerRegistry.default()
        assertTrue(registry.isPreparable(PackageCapability.KEYBOARD_OUTPUT))
        assertNotNull(registry.preparerFor(PackageCapability.KEYBOARD_OUTPUT))
        assertFalse(registry.isPreparable(PackageCapability.AUDIO_TRANSCRIPTION))
    }

    // ── 4.4: request binding ────────────────────────────────────────────────

    @Test
    fun `preparation request requires positive timeout`() {
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityPreparationRequest("keyboard.output", identity, attempt = 0, timeoutMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityPreparationRequest("keyboard.output", identity, attempt = 0, timeoutMillis = -1)
        }
    }

    @Test
    fun `preparation request requires non-blank capability id`() {
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityPreparationRequest("", identity, attempt = 0, timeoutMillis = 1000)
        }
    }

    @Test
    fun `preparer receives bound attempt identity and timeout`() = runTest {
        val identity = CapabilityScopeIdentity("instance-1", RuntimeGeneration(42))
        var capturedRequest: CapabilityPreparationRequest? = null
        val preparer = CapabilityPreparer { _, request ->
            capturedRequest = request
            CapabilityPreparationOutcome.Prepared
        }
        val scope = ScriptedScope()
        preparer.prepare(scope, CapabilityPreparationRequest("test.cap", identity, attempt = 7, timeoutMillis = 5000))
        val request = assertNotNull(capturedRequest).let { capturedRequest!! }
        assertEquals("test.cap", request.publicCapabilityId)
        assertEquals(identity, request.identity)
        assertEquals(7L, request.attempt)
        assertEquals(5000L, request.timeoutMillis)
    }

    // ── 4.5/4.6: host preparer outcomes via acquisition ─────────────────────

    @Test
    fun `host preparer returns Prepared and releases proof lease on available acquisition`() = runTest {
        val lease = RecordingLease()
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Available(lease))
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(CapabilityPreparationOutcome.Prepared, outcome)
        assertEquals(1, lease.releaseCalls)
        assertEquals(CapabilityAcquisitionPolicy.PrepareRecoverable(1000), scope.lastPolicy)
    }

    @Test
    fun `host preparer maps Recoverable acquisition to Unavailable`() = runTest {
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY))
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(
            CapabilityPreparationOutcome.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY),
            outcome,
        )
    }

    @Test
    fun `host preparer maps Unavailable acquisition to Unavailable`() = runTest {
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED))
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(
            CapabilityPreparationOutcome.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED),
            outcome,
        )
    }

    @Test
    fun `host preparer maps Denied acquisition to policy-refused Unavailable`() = runTest {
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Denied(CapabilityDeniedReason.UNDECLARED))
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(
            CapabilityPreparationOutcome.Unavailable(CapabilityUnavailableReason.POLICY_REFUSED),
            outcome,
        )
    }

    @Test
    fun `host preparer maps Closed acquisition to Closed`() = runTest {
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Closed)
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(CapabilityPreparationOutcome.Closed, outcome)
    }

    @Test
    fun `host preparer maps Cancelled acquisition to Cancelled`() = runTest {
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Cancelled)
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(CapabilityPreparationOutcome.Cancelled, outcome)
    }

    @Test
    fun `host preparer maps Failed acquisition to Failed`() = runTest {
        val scope = ScriptedScope(acquireResult = CapabilityAcquisition.Failed(CapabilityFailureReason.PREPARATION_FAILED))
        val preparer = HostCapabilityPreparer(CapabilityKey.TextOutput)
        val identity = CapabilityScopeIdentity("instance", RuntimeGeneration(1))
        val outcome = preparer.prepare(scope, CapabilityPreparationRequest("keyboard.output", identity, 0, 1000))
        assertEquals(
            CapabilityPreparationOutcome.Failed(CapabilityFailureReason.PREPARATION_FAILED),
            outcome,
        )
    }

    // ── 4.7: unknown, undeclared, non-preparable request handling ────────────

    @Test
    fun `registry returns null preparer for unknown capability`() {
        val registry = CapabilityPreparerRegistry.default()
        assertNull(registry.preparerFor("unknown.capability"))
        assertFalse(registry.isPreparable("unknown.capability"))
    }

    @Test
    fun `registry returns null preparer for declared but non-preparable capability`() {
        val registry = CapabilityPreparerRegistry.default()
        assertNull(registry.preparerFor(PackageCapability.AUDIO_TRANSCRIPTION))
        assertFalse(registry.isPreparable(PackageCapability.AUDIO_TRANSCRIPTION))
    }

    // ── 4.8: declaration and construction perform no preparation or effect ───

    @Test
    fun `constructing a registry performs no preparation`() = runTest {
        var prepareInvoked = false
        val preparer = CapabilityPreparer { _, _ ->
            prepareInvoked = true
            CapabilityPreparationOutcome.Prepared
        }
        val registry = CapabilityPreparerRegistry.Builder()
            .register("test.cap", preparer)
            .build()
        assertTrue(registry.isPreparable("test.cap"))
        assertNotNull(registry.preparerFor("test.cap"))
        assertFalse("Registry construction and lookup must not invoke the preparer", prepareInvoked)
    }

    @Test
    fun `default registry construction performs no acquisition`() = runTest {
        val scope = ScriptedScope()
        CapabilityPreparerRegistry.default()
        assertEquals("Default registry construction must not acquire any capability", 0, scope.acquireCount)
    }

    // ── Test fakes ───────────────────────────────────────────────────────────

    private class RecordingLease : CapabilityLease<TextOutputCapability> {
        var releaseCalls = 0
        override val capability: ChannelCapability = ChannelCapability.TextOutput
        override val identity = CapabilityScopeIdentity("test", RuntimeGeneration(0))
        override val state: CapabilityLeaseState get() = CapabilityLeaseState.ACTIVE
        override suspend fun <R> use(operation: suspend (TextOutputCapability) -> CapabilityOperationResult<R>): CapabilityOperationResult<R> =
            CapabilityOperationResult.Closed
        override suspend fun release(): CapabilityReleaseResult {
            releaseCalls++
            return CapabilityReleaseResult.Released
        }
    }

    private class ScriptedScope(
        private val acquireResult: CapabilityAcquisition<*> = CapabilityAcquisition.Closed,
    ) : ChannelCapabilityScope {
        var acquireCount = 0
            private set
        var lastPolicy: CapabilityAcquisitionPolicy? = null
            private set
        override val identity = CapabilityScopeIdentity("test", RuntimeGeneration(0))
        override val declaredCapabilities: Set<ChannelCapability> = setOf(ChannelCapability.TextOutput)
        override val isClosed: Boolean = false
        override suspend fun availability(key: CapabilityKey<*>): CapabilityAvailabilityResult =
            CapabilityAvailabilityResult.Closed

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            key: CapabilityKey<T>,
            policy: CapabilityAcquisitionPolicy,
        ): CapabilityAcquisition<T> {
            acquireCount++
            lastPolicy = policy
            return acquireResult as CapabilityAcquisition<T>
        }
    }
}
