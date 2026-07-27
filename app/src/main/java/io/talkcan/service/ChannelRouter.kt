package io.talkcan.service

import io.talkcan.audio.ChannelInputAcceptance

/** Routes audio input session preparation to the selected channel runtime. */
interface ChannelRouter {
    suspend fun prepareInput(channelId: String): ChannelInputAcceptance
}

/**
 * Host-only marker carried by an accepted target. The audio terminal owner calls it exactly once
 * after target notification and final route release, before clearing the terminal session.
 */
interface CommittedTargetLeaseOwner {
    suspend fun releaseCommittedTargetLease()
}
