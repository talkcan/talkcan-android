package io.talkcan

import io.talkcan.model.ChannelCatalogueError
import io.talkcan.model.ChannelRepositoryError
import io.talkcan.model.ChannelRepositoryMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRepositoryMutationFailureMessageTest {
    @Test
    fun `missing service returns the host unavailable message`() {
        val result: ChannelRepositoryMutationResult? = null

        assertEquals("Channel service is unavailable.", result.failureMessage())
    }

    @Test
    fun `successful mutation has no form failure message`() {
        assertNull(ChannelRepositoryMutationResult.Success.failureMessage())
    }

    @Test
    fun `failed mutation propagates its exact repository error message`() {
        val error = ChannelRepositoryError.Mutation(ChannelCatalogueError.UnknownChannelId("missing-instance"))

        assertEquals(
            error.message,
            ChannelRepositoryMutationResult.Failure(error).failureMessage(),
        )
    }
}
