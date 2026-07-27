package io.talkcan.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNowPlayingMetadataBuilderTest {
    private val drawables = CarNowPlayingDrawables(
        notReadyResId = 1001,
        readyResId = 1002,
        recordingResId = 1003,
        finalizingResId = 1004,
    )

    @Test
    fun `unavailable active channel retains its name but presents non-ready metadata`() {
        val metadata = buildCarNowPlayingMetadata(
            activeChannelName = "Unavailable but retained",
            state = CarMediaPttState.NotReady,
            pendingCount = 7,
            appArtist = "Talkcan",
            notReadyFallbackTitle = "No channel",
            drawables = drawables,
        )

        assertEquals("Unavailable but retained", metadata.title)
        assertEquals("Talkcan", metadata.artist)
        assertEquals("NOT READY · 7 pending", metadata.subtitle)
        assertEquals(1001, metadata.drawableResId)
    }

    @Test
    fun `metadata status and artwork change with runtime state without changing active instance presentation`() {
        data class Case(
            val state: CarMediaPttState,
            val subtitle: String,
            val drawable: Int,
        )

        listOf(
            Case(CarMediaPttState.NotReady, "NOT READY", 1001),
            Case(CarMediaPttState.Ready, "ACTIVE", 1002),
            Case(CarMediaPttState.Recording, "RECORDING", 1003),
            Case(CarMediaPttState.Finalizing, "FINALIZING", 1004),
        ).forEach { case ->
            val metadata = buildCarNowPlayingMetadata(
                activeChannelName = "Stable active instance",
                state = case.state,
                pendingCount = 0,
                appArtist = "Talkcan",
                notReadyFallbackTitle = "No channel",
                drawables = drawables,
            )

            assertEquals(case.state.name, "Stable active instance", metadata.title)
            assertEquals(case.state.name, case.subtitle, metadata.subtitle)
            assertEquals(case.state.name, case.drawable, metadata.drawableResId)
        }
    }

    @Test
    fun `pending suffix is omitted rather than truncating the status pill when it exceeds metadata budget`() {
        val oversizedPill = "P".repeat(CAR_NOW_PLAYING_SUBTITLE_LIMIT)

        assertEquals(oversizedPill, appendPending(oversizedPill, 1))
        assertEquals("ACTIVE · 2 pending", appendPending("ACTIVE", 2))
        assertTrue(appendPending("ACTIVE", 2).length <= CAR_NOW_PLAYING_SUBTITLE_LIMIT)
    }
}