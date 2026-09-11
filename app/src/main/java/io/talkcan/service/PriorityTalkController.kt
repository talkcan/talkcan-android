package io.talkcan.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.asStateFlow

/** One held priority interaction. Release during preemption never starts a microphone. */
internal class PriorityTalkController(
    private val scope: CoroutineScope,
    private val prepare: suspend () -> Boolean,
    private val onFinished: () -> Unit = {},
    private val start: suspend (String) -> Unit,
    private val stop: suspend () -> Unit,
) {
    private val mutableTarget = MutableStateFlow<String?>(null)
    val target = mutableTarget.asStateFlow()
    private var release: CompletableDeferred<Unit>? = null

    fun press(channelId: String) {
        if (release != null) return
        val signal = CompletableDeferred<Unit>()
        release = signal
        mutableTarget.value = channelId
        scope.launch {
            try {
                if (prepare() && !signal.isCompleted) coroutineScope {
                    val starting = async { start(channelId) }
                    try {
                        select<Unit> {
                            starting.onAwait { }
                            signal.onAwait { }
                        }
                    } finally {
                        starting.cancelAndJoin()
                    }
                }
                signal.await()
            } finally {
                withContext(NonCancellable) {
                    try {
                        stop()
                    } finally {
                        mutableTarget.value = null
                        release = null
                        onFinished()
                    }
                }
            }
        }
    }

    fun release() {
        release?.complete(Unit)
    }
}
