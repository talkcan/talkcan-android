package io.talkcan.service

import io.talkcan.model.ChannelImplementationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PttDispatchDecisionTest {
    @Test
    fun `PTT admission depends only on active instance preparation availability`() {
        data class Case(
            val name: String,
            val instanceId: String,
            val preparation: ChannelPreparationAvailability,
            val expected: PttDispatchDecision,
        )

        val cases = listOf(
            Case(
                name = "ready provider",
                instanceId = "instance-ready",
                preparation = ChannelPreparationAvailability.Available,
                expected = PttDispatchDecision.Dispatch("instance-ready"),
            ),
            Case(
                name = "recoverable provider",
                instanceId = "instance-recoverable",
                preparation = ChannelPreparationAvailability.Recoverable(
                    ChannelPreparationReason.ProviderInitialising,
                ),
                expected = PttDispatchDecision.Dispatch("instance-recoverable"),
            ),
            Case(
                name = "unavailable provider",
                instanceId = "instance-unavailable",
                preparation = ChannelPreparationAvailability.Unavailable(
                    ChannelPreparationReason.RuntimeClosed,
                ),
                expected = PttDispatchDecision.ErrorBeep("instance-unavailable"),
            ),
        )

        cases.forEach { case ->
            val snapshot = RuntimeRegistrySnapshot(
                activeChannelId = case.instanceId,
                entries = listOf(runtimeSnapshot(case.instanceId, case.preparation)),
            )

            assertEquals(case.name, case.expected, decidePttDispatch(snapshot))
        }
    }

    @Test
    fun `unknown active instance is refused instead of dispatching to another available provider`() {
        val snapshot = RuntimeRegistrySnapshot(
            activeChannelId = "unknown-instance",
            entries = listOf(runtimeSnapshot("available-sibling", ChannelPreparationAvailability.Available)),
        )

        assertEquals(
            PttDispatchDecision.ErrorBeep("unknown-instance"),
            decidePttDispatch(snapshot),
        )
    }

    @Test
    fun `empty active selection does not admit PTT`() {
        assertNull(
            decidePttDispatch(
                RuntimeRegistrySnapshot(
                    activeChannelId = "",
                    entries = listOf(runtimeSnapshot("available", ChannelPreparationAvailability.Available)),
                ),
            ),
        )
    }

    private fun runtimeSnapshot(
        id: String,
        preparation: ChannelPreparationAvailability,
    ) = ChannelRuntimeSnapshot(
        id = id,
        name = "Provider-neutral $id",
        implementationId = ChannelImplementationId("test:$id"),
        enabled = true,
        preparation = preparation,
        executionStatus = ChannelExecutionStatus.IDLE,
    )
}
