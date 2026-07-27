package io.talkcan.service

import io.talkcan.model.RawButtonEvent
import io.talkcan.model.selectChannelByOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarSkipDecisionTest {
    @Test
    fun `car skip actions permit channel navigation before readiness while protecting active capture`() {
        data class Case(
            val state: CarMediaPttState,
            val next: CarSkipAction,
            val previous: CarSkipAction,
        )

        listOf(
            Case(CarMediaPttState.NotReady, CarSkipAction.NextChannel, CarSkipAction.PrevChannel),
            Case(CarMediaPttState.Ready, CarSkipAction.NextChannel, CarSkipAction.PrevChannel),
            Case(CarMediaPttState.Recording, CarSkipAction.NoOp, CarSkipAction.NoOp),
            Case(CarMediaPttState.Finalizing, CarSkipAction.SkipMessage, CarSkipAction.ReplayMessage),
        ).forEach { case ->
            assertEquals(case.state.name, case.next to case.previous, CarSkipDecision.fromState(case.state))
        }
    }

    @Test
    fun `channel traversal retains unavailable positions so availability changes cannot renumber car controls`() {
        val catalogueIds = listOf("ready-before", "unavailable-retained", "ready-after")

        assertEquals("unavailable-retained", selectChannelByOffset(catalogueIds, "ready-before", +1))
        assertEquals("ready-after", selectChannelByOffset(catalogueIds, "unavailable-retained", +1))
        assertEquals("ready-before", selectChannelByOffset(catalogueIds, "unavailable-retained", -1))
        assertEquals("ready-after", selectChannelByOffset(catalogueIds, "ready-after", +1))
        assertEquals("ready-before", selectChannelByOffset(catalogueIds, "ready-before", -1))
    }

    @Test
    fun `empty catalogue has no offset target`() {
        assertNull(selectChannelByOffset(emptyList(), "any-instance", +1))
    }

    @Test
    fun `RSM volume offsets remain inverse to physical list direction`() {
        assertEquals(-1, rsmChannelOffset(RawButtonEvent.VolumeUpClicked))
        assertEquals(1, rsmChannelOffset(RawButtonEvent.VolumeDownClicked))
        assertNull(rsmChannelOffset(RawButtonEvent.PttPressed))
    }
}