package io.talkcan.lua.actor

import io.talkcan.lua.LuaOperationHandle
import java.util.concurrent.atomic.AtomicReference

/**
 * Exactly-once ownership for one suspended operation token.
 *
 * Each token accepts at most one terminal completion, cancellation, or close
 * outcome. A resume, cancel, or close for a foreign or stale token returns a
 * typed outcome without entering Lua. Duplicate terminal requests return
 * [ActorTerminalResult.AlreadyCompleted] without a second Lua effect.
 *
 * The scheduler resumes at most one coroutine per ready dequeue and at most
 * one terminal completion per token. Terminal admission is exactly-once: the
 * sole winner produces the Lua effect; the loser observes a typed terminal
 * result.
 *
 * @property kernelHandle The exact [LuaOperationHandle] returned by the
 *   kernel's [LuaKernelOutcome.Yielded], carrying the kernel-issued
 *   operation ID, coroutine ID, and owning state handle. Resume/cancel
 *   passes this identical handle to the bridge — the actor never fabricates
 *   a native operation ID.
 */
internal class ActorOperationToken(
    val identity: ActorOperationIdentity,
    val kernelHandle: LuaOperationHandle,
) {
    private val terminal = AtomicReference<ActorTerminal?>(null)

    val isCompleted: Boolean
        get() = terminal.get() != null

    /**
     * Attempt to set the terminal outcome. Returns the winning terminal if
     * this call was first, or [ActorTerminalResult.AlreadyCompleted] with the
     * existing terminal if another caller won the race.
     */
    fun complete(outcome: ActorTerminal): ActorTerminalResult {
        val won = terminal.compareAndSet(null, outcome)
        return if (won) {
            ActorTerminalResult.Completed(outcome)
        } else {
            ActorTerminalResult.AlreadyCompleted(terminal.get()!!)
        }
    }

    /**
     * The current terminal outcome, or null if the token is still suspended.
     */
    fun terminalOutcome(): ActorTerminal? = terminal.get()
}

/**
 * Terminal outcome for one operation token. Exactly one of these wins per
 * token.
 */
internal sealed class ActorTerminal {
    abstract val identity: ActorOperationIdentity?

    data class Completed(
        override val identity: ActorOperationIdentity,
        val value: String,
    ) : ActorTerminal()

    data class Failed(
        override val identity: ActorOperationIdentity,
        val diagnostic: String,
    ) : ActorTerminal()

    data class TimedOut(
        override val identity: ActorOperationIdentity,
    ) : ActorTerminal()

    data class Cancelled(
        override val identity: ActorOperationIdentity,
    ) : ActorTerminal()

    data object Closed : ActorTerminal() {
        override val identity: ActorOperationIdentity? get() = null
    }

    data object Stale : ActorTerminal() {
        override val identity: ActorOperationIdentity? get() = null
    }
}

/**
 * Result of attempting to set or observe the terminal outcome of a token.
 */
internal sealed class ActorTerminalResult {
    data class Completed(val terminal: ActorTerminal) : ActorTerminalResult()
    data class AlreadyCompleted(val terminal: ActorTerminal) : ActorTerminalResult()
}

/**
 * Typed outcome for a resume, cancel, or close request on an operation token.
 */
internal sealed class ActorOperationOutcome {
    data class Resumed(val terminal: ActorTerminal) : ActorOperationOutcome()
    data object AlreadyCompleted : ActorOperationOutcome()
    data object Stale : ActorOperationOutcome()
    data object InvalidOwner : ActorOperationOutcome()
    data object Closed : ActorOperationOutcome()
}
