package io.talkcan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCardPresentationTest {
    @Test
    fun `channel cards present selection and availability with intentional precedence`() {
        val cases = listOf(
            Case(
                name = "selected available",
                isActive = true,
                isAvailable = true,
                expectedLabel = "Selected",
                expectedTone = ChannelCardTone.Primary,
            ),
            Case(
                name = "selected unavailable remains active",
                isActive = true,
                isAvailable = false,
                expectedLabel = "Selected",
                expectedTone = ChannelCardTone.Primary,
            ),
            Case(
                name = "unselected available",
                isActive = false,
                isAvailable = true,
                expectedLabel = "Ready",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "unselected unavailable",
                isActive = false,
                isAvailable = false,
                expectedLabel = "Unavailable",
                expectedTone = ChannelCardTone.Secondary,
            ),
        )

        cases.forEach { case ->
            val presentation = channelCardPresentation(
                isActive = case.isActive,
                isAvailable = case.isAvailable,
            )

            assertEquals("${case.name} label", case.expectedLabel, presentation.statusLabel)
            assertEquals("${case.name} tone", case.expectedTone, presentation.tone)
        }
    }

    @Test
    fun `pending response label omits zero renders singular for one and plural for many`() {
        assertNull("zero pending must not render a pill", pendingResponseLabel(0))
        assertEquals("1 pending response", pendingResponseLabel(1))
        assertEquals("2 pending responses", pendingResponseLabel(2))
        assertEquals("3 pending responses", pendingResponseLabel(3))
        assertEquals("42 pending responses", pendingResponseLabel(42))
    }

    @Test
    fun `pending response label treats negative counts as zero`() {
        assertNull(pendingResponseLabel(-1))
        assertNull(pendingResponseLabel(-100))
    }

    private data class Case(
        val name: String,
        val isActive: Boolean,
        val isAvailable: Boolean,
        val expectedLabel: String,
        val expectedTone: ChannelCardTone,
    )
}
