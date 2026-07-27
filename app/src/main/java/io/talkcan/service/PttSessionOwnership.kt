package io.talkcan.service

import io.talkcan.model.PttSource

internal fun ownsPttRelease(active: PttSource?, requested: PttSource, failSafe: Boolean = false): Boolean =
    failSafe || active == requested
