package io.talkcan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCardPresentationTest {
    @Test
    fun `channel cards present selection availability and PTT state with intentional precedence`() {
        val cases = listOf(
            Case(
                name = "selected available",
                isActive = true,
                isAvailable = true,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "Selected",
                expectedTone = ChannelCardTone.Primary,
            ),
            Case(
                name = "selected unavailable remains active",
                isActive = true,
                isAvailable = false,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "Selected",
                expectedTone = ChannelCardTone.Primary,
            ),
            Case(
                name = "unselected available",
                isActive = false,
                isAvailable = true,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "Ready",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "unselected unavailable",
                isActive = false,
                isAvailable = false,
                isPttActive = false,
                isLocked = false,
                expectedLabel = "Unavailable",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "PTT overrides selected unavailable state",
                isActive = true,
                isAvailable = false,
                isPttActive = true,
                isLocked = false,
                expectedLabel = "Recording",
                expectedTone = ChannelCardTone.Secondary,
            ),
            Case(
                name = "LOCKED overrides PTT selected unavailable state",
                isActive = true,
                isAvailable = false,
                isPttActive = true,
                isLocked = true,
                expectedLabel = "Locked",
                expectedTone = ChannelCardTone.Secondary,
            ),
        )

        cases.forEach { case ->
            val presentation = channelCardPresentation(
                isActive = case.isActive,
                isAvailable = case.isAvailable,
                isPttActive = case.isPttActive,
                isLocked = case.isLocked,
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
        val isPttActive: Boolean,
        val isLocked: Boolean,
        val expectedLabel: String,
        val expectedTone: ChannelCardTone,
    )
}
