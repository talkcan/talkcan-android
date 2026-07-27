package io.talkcan.lua

import io.talkcan.work.DurableWorkBounds
import io.talkcan.work.DurableWorkCoordinator
import io.talkcan.work.DurableWorkStore
import io.talkcan.work.WorkEffectResult
import io.talkcan.work.WorkEffectState
import io.talkcan.work.WorkInstanceId
import io.talkcan.work.WorkMapEntry
import io.talkcan.work.WorkQueueId
import io.talkcan.work.WorkQueuePartition
import io.talkcan.work.WorkRepositoryId
import io.talkcan.work.WorkState
import io.talkcan.work.WorkStoreResult
import io.talkcan.work.WorkTerminalClass
import io.talkcan.work.WorkValue
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 12.10: durable work host adapter conformance — Lua observes only opaque
 * job tokens, detached payloads in the shared tagged [WorkValue] encoding, and
 * normalized `E_*` codes; work ids, sequences, partitions, and leases never
 * appear in a resume value. Exactly-once terminals, FIFO delivery, effect-bracket
 * memoization, and sibling-holder isolation are defended at the resume envelope.
 */
class WorkHostAdapterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val workIds = AtomicInteger()
    private val repositoryId = WorkRepositoryId(13L)
    private val instanceId = WorkInstanceId("instance-1")
    private val partition = WorkQueuePartition(repositoryId, instanceId, WorkQueueId("turns"))

    private fun newStore(bounds: DurableWorkBounds = DurableWorkBounds()): DurableWorkStore =
        DurableWorkStore(folder.root, bounds) { "work-${workIds.getAndIncrement()}" }.also {
            it.load()
        }

    private fun newAdapter(
        store: DurableWorkStore,
        coordinator: DurableWorkCoordinator,
        holderToken: String = "holder-A",
        jobTokenGenerator: () -> String,
    ): WorkHostAdapter = WorkHostAdapter(
        store = store,
        coordinator = coordinator,
        repositoryId = repositoryId,
        instanceId = instanceId,
        holderToken = holderToken,
        declaredQueues = setOf("turns"),
        clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
        jobTokenGenerator = jobTokenGenerator,
    )

    private fun claim(
        kind: HostOperationKind,
        queue: String? = "turns",
        payloadJson: String? = null,
        job: String? = null,
        effectKey: String? = null,
        fingerprint: String? = null,
        resultOk: Boolean = false,
        resultJson: String? = null,
        reasonJson: String? = null,
    ): HostOperationClaim.Admitted = HostOperationClaim.Admitted(
        requestId = 1,
        kind = kind,
        audioToken = null,
        text = null,
        language = null,
        voice = null,
        speed = 1.0,
        delaySeconds = 0.0,
        queue = queue,
        payloadJson = payloadJson,
        job = job,
        effectKey = effectKey,
        fingerprint = fingerprint,
        resultOk = resultOk,
        resultJson = resultJson,
        reasonJson = reasonJson,
    )

    private fun textJson(value: String): String =
        JSONObject().put("t", "text").put("v", value).toString()

    private suspend fun submit(adapter: WorkHostAdapter, payloadJson: String, queue: String? = "turns"): TypedHostCompletion =
        adapter.complete(claim(HostOperationKind.WORK_SUBMIT, queue = queue, payloadJson = payloadJson))

    private suspend fun receive(adapter: WorkHostAdapter, queue: String? = "turns"): TypedHostCompletion =
        adapter.complete(claim(HostOperationKind.WORK_RECEIVE, queue = queue))

    private suspend fun begin(adapter: WorkHostAdapter, job: String, key: String, fingerprint: String): TypedHostCompletion =
        adapter.complete(claim(HostOperationKind.WORK_BEGIN_EFFECT, job = job, effectKey = key, fingerprint = fingerprint))

    private suspend fun commit(adapter: WorkHostAdapter, job: String, key: String, resultOk: Boolean, resultJson: String): TypedHostCompletion =
        adapter.complete(
            claim(HostOperationKind.WORK_COMMIT_EFFECT, job = job, effectKey = key, resultOk = resultOk, resultJson = resultJson),
        )

    private suspend fun complete(adapter: WorkHostAdapter, job: String): TypedHostCompletion =
        adapter.complete(claim(HostOperationKind.WORK_COMPLETE, job = job))

    private suspend fun fail(adapter: WorkHostAdapter, job: String, reasonJson: String): TypedHostCompletion =
        adapter.complete(claim(HostOperationKind.WORK_FAIL, job = job, reasonJson = reasonJson))

    /** Submit first, then receive, so receive never suspends; returns the minted job token. */
    private suspend fun submitAndReceive(
        adapter: WorkHostAdapter,
        payloadJson: String = textJson("hello"),
        expectedJobToken: String = "job-0",
    ): String {
        submit(adapter, payloadJson).doc()
        val doc = receive(adapter).doc()
        assertEquals(expectedJobToken, doc.getString("jobId"))
        return expectedJobToken
    }

    private fun TypedHostCompletion.doc(): JSONObject {
        assertTrue("Expected success doc, was failure '$value'", success)
        return JSONObject(value)
    }

    private fun TypedHostCompletion.errorCode(): String {
        assertFalse("Expected E_* failure, was success doc '$value'", success)
        return value
    }

    private fun <T> WorkStoreResult<T>.success(): T = when (this) {
        is WorkStoreResult.Success -> value
        is WorkStoreResult.Failure -> throw AssertionError("Expected success, was Failure($failure)")
    }

    @Test
    fun submitAssignsMonotonicSequencesAndPersistsFifoPayloads() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }

        assertEquals(0L, submit(adapter, textJson("hello")).doc().getLong("sequence"))
        assertEquals(1L, submit(adapter, textJson("world")).doc().getLong("sequence"))

        val snapshot = store.snapshot(partition).success()
        assertEquals(listOf(0L, 1L), snapshot.works.map { it.sequence })
        assertEquals(
            listOf<WorkValue>(WorkValue.Text("hello"), WorkValue.Text("world")),
            snapshot.works.map { it.payload },
        )
        assertEquals(2L, snapshot.record.nextSequence)
    }

    @Test
    fun submitAcceptsCanonicalRustMapEncoding() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val adapter = newAdapter(store, coordinator) { "job-0" }
        val payloadJson =
            """{"t":"map","v":[["schema_version",{"t":"int","v":1}],["user_text",{"t":"text","v":"hello"}]]}"""

        assertEquals(0L, submit(adapter, payloadJson).doc().getLong("sequence"))
        assertEquals(
            WorkValue.Map(
                listOf(
                    WorkMapEntry("schema_version", WorkValue.Integer(1)),
                    WorkMapEntry("user_text", WorkValue.Text("hello")),
                ),
            ),
            store.snapshot(partition).success().works.single().payload,
        )
    }

    @Test
    fun submitRejectsMalformedPayloadAndUnknownQueues() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }

        assertEquals("E_INVALID_VALUE", submit(adapter, "nope").errorCode())
        assertEquals("E_INVALID_ARGUMENT", submit(adapter, textJson("x"), queue = null).errorCode())
        assertEquals("E_INVALID_ARGUMENT", submit(adapter, textJson("x"), queue = "other").errorCode())

        // Rejected submissions leave no partial state: the next valid submit is sequence 0.
        assertEquals(0L, submit(adapter, textJson("ok")).doc().getLong("sequence"))
    }

    @Test
    fun submitRejectsWhenQueueCapacityExhausted() = runTest {
        val store = newStore(DurableWorkBounds(maxNonterminalItemsPerQueue = 1))
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }

        assertEquals(0L, submit(adapter, textJson("one")).doc().getLong("sequence"))
        assertEquals("E_BUSY", submit(adapter, textJson("two")).errorCode())
    }

    @Test
    fun receiveDeliversOpaqueJobTokenWithDetachedTaggedPayload() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }

        submit(adapter, textJson("hello")).doc()
        val doc = receive(adapter).doc()

        // The resume carries exactly the opaque token (minted by the host generator,
        // never the durable work id) plus the detached payload — no ids, no leases.
        assertEquals(setOf("jobId", "payloadJson"), doc.keySet())
        assertEquals("job-0", doc.getString("jobId"))
        assertTrue(JSONObject(doc.getString("payloadJson")).similar(JSONObject(textJson("hello"))))

        // A guessed or unknown job token never resolves a binding.
        assertEquals("E_DENIED", begin(adapter, job = "job-404", key = "send", fingerprint = "fp-1").errorCode())
    }

    @Test
    fun siblingHolderCannotUseForeignJobToken() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val aTokens = AtomicInteger()
        val adapterA = newAdapter(store, coordinator, holderToken = "holder-A") { "job-${aTokens.getAndIncrement()}" }
        val job = submitAndReceive(adapterA)

        val bTokens = AtomicInteger()
        val adapterB = newAdapter(store, coordinator, holderToken = "holder-B") { "job-b-${bTokens.getAndIncrement()}" }
        assertEquals("E_DENIED", begin(adapterB, job = job, key = "send", fingerprint = "fp-1").errorCode())
        assertEquals("E_DENIED", complete(adapterB, job = job).errorCode())

        // Holder A's binding is untouched by B's rejected attempts.
        assertEquals(true, begin(adapterA, job = job, key = "send", fingerprint = "fp-1").doc().getBoolean("started"))
    }

    @Test
    fun effectBracketMemoizesCommitAndRejectsFingerprintReuse() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }
        val job = submitAndReceive(adapter)

        assertTrue(begin(adapter, job, key = "send", fingerprint = "fp-1").doc().getBoolean("started"))
        val ok = commit(adapter, job, key = "send", resultOk = true, resultJson = textJson("ok-val")).doc()
        assertTrue(ok.getBoolean("ok"))

        // Same key + same fingerprint replays the stored result without re-running the effect.
        val replay = begin(adapter, job, key = "send", fingerprint = "fp-1").doc()
        assertTrue(replay.getBoolean("replay"))
        assertTrue(replay.getBoolean("resultOk"))
        assertTrue(JSONObject(replay.getString("resultJson")).similar(JSONObject(textJson("ok-val"))))

        // Same key with a different fingerprint is an incompatible duplicate.
        assertEquals("E_STORE", begin(adapter, job, key = "send", fingerprint = "fp-2").errorCode())

        val effect = store.snapshot(partition).success().effects.single()
        assertEquals(WorkEffectState.COMMITTED, effect.state)
        assertEquals(WorkEffectResult.Success(WorkValue.Text("ok-val")), effect.result)
    }

    @Test
    fun completeIsExactlyOnceAndDropsTheJobBinding() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }
        val job = submitAndReceive(adapter)

        assertTrue(complete(adapter, job).doc().getBoolean("ok"))

        // Exactly-once: the retired token never drives a second terminal transition,
        // and no later effect can start on it (binding dropped).
        assertFalse(complete(adapter, job).success)
        assertEquals("E_DENIED", begin(adapter, job = job, key = "send", fingerprint = "fp-1").errorCode())

        val tombstones = store.tombstones(partition).success()
        assertEquals(1, tombstones.size)
        assertEquals(WorkTerminalClass.COMPLETED, tombstones.single().terminalClass)
        assertNull(tombstones.single().reasonTag)
        assertEquals(1000L, tombstones.single().terminatedAtMillis)
        assertTrue(store.snapshot(partition).success().works.isEmpty())
    }

    @Test
    fun failPersistsBoundedUtf8ReasonTagWithoutSplittingCodePoints() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }
        val job = submitAndReceive(adapter)

        // 81 UTF-8 bytes; the 64-byte boundary falls inside the 32nd two-byte "ñ".
        val reason = "a" + "ñ".repeat(40)
        assertTrue(reason.toByteArray(StandardCharsets.UTF_8).size > 64)

        assertTrue(fail(adapter, job, reasonJson = textJson(reason)).doc().getBoolean("ok"))

        val tombstone = store.tombstones(partition).success().single()
        assertEquals(WorkTerminalClass.FAILED, tombstone.terminalClass)
        val tag = checkNotNull(tombstone.reasonTag)
        assertEquals("a" + "ñ".repeat(31), tag)
        assertEquals(63, tag.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun receiveOnClosedPartitionReturnsClosedDoc() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }

        coordinator.close(partition)
        val doc = receive(adapter).doc()
        assertEquals(setOf("closed"), doc.keySet())
        assertTrue(doc.getBoolean("closed"))
    }

    @Test
    fun closeReleasesActiveClaimBackToQueuedWithoutStranding() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }
        val job = submitAndReceive(adapter)

        val active = store.snapshot(partition).success().works.single()
        assertEquals(WorkState.CLAIMED, active.state)
        assertEquals("holder-A", active.leaseHolder)

        adapter.close()
        assertEquals(0, adapter.activeJobCount)

        val released = store.snapshot(partition).success().works.single()
        assertEquals(WorkState.QUEUED, released.state)
        assertNull(released.leaseHolder)

        // The dropped binding fails denied, and the released item is claimable again
        // in place (FIFO position preserved, next generation token minted).
        assertEquals("E_DENIED", complete(adapter, job).errorCode())
        assertEquals("job-1", receive(adapter).doc().getString("jobId"))
    }

    @Test
    fun closeAfterStartedEffectTerminatesIndeterminate() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }
        val job = submitAndReceive(adapter)

        assertTrue(begin(adapter, job, key = "send", fingerprint = "fp-1").doc().getBoolean("started"))
        adapter.close()

        assertEquals(0, adapter.activeJobCount)
        val tombstone = store.tombstones(partition).success().single()
        assertEquals(WorkTerminalClass.INDETERMINATE, tombstone.terminalClass)
        assertTrue(store.snapshot(partition).success().works.isEmpty())
    }

    @Test
    fun nonWorkClaimKindIsDenied() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val jobTokens = AtomicInteger()
        val adapter = newAdapter(store, coordinator) { "job-${jobTokens.getAndIncrement()}" }

        assertEquals("E_DENIED", adapter.complete(claim(HostOperationKind.KEYBOARD_SEND_TEXT)).errorCode())
    }
}
