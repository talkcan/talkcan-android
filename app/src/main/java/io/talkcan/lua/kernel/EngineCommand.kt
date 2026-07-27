package io.talkcan.lua.kernel

import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One typed command submitted from an arbitrary caller thread into a single Lua
 * state's bounded engine inbox. The owning engine thread is the only code that
 * ever dequeues and executes a command, so all command execution for a state is
 * serialized on that state's thread.
 *
 * Every command instance carries its own single-slot reply channel: the engine
 * completes it exactly once with a normalized [LuaKernelOutcome], and the
 * submitting caller blocks on it for a bounded time. Later kernel tasks replace
 * the engine's per-command handlers without changing this command topology.
 */
internal sealed class EngineCommand {

    private val reply: ArrayBlockingQueue<LuaKernelOutcome> = ArrayBlockingQueue(1)

    /** Engine-side: publish the terminal outcome for this command. */
    fun complete(outcome: LuaKernelOutcome) {
        reply.offer(outcome)
    }

    /** Caller-side: bounded wait for the engine's answer; null means no answer in time. */
    fun awaitReply(timeoutMs: Long): LuaKernelOutcome? = reply.poll(timeoutMs, TimeUnit.MILLISECONDS)

    class Load(val source: String, val entrypoint: String) : EngineCommand()

    class Start : EngineCommand()

    class Resume(
        val operation: LuaOperationHandle,
        val success: Boolean,
        val value: String,
        val spawnAdmission: LuaSpawnAdmission,
    ) : EngineCommand()

    class Cancel(val operation: LuaOperationHandle) : EngineCommand()

    class Interrupt : EngineCommand()

    class LoadProgramImage(
        val entryPoint: String,
        val sourceMap: Map<String, String>,
    ) : EngineCommand()

    class InvokeStartupCallback(
        val callbackName: String,
        val configJson: String,
        val spawnAdmission: LuaSpawnAdmission,
    ) : EngineCommand()

    class InvokeCallback(
        val callbackName: String,
        val argumentsJson: String,
        val spawnAdmission: LuaSpawnAdmission,
    ) : EngineCommand()

    class InvokeInputCallback(
        val callbackName: String,
        val argumentsJson: String,
        val capturedAudioToken: String,
        val spawnAdmission: LuaSpawnAdmission,
    ) : EngineCommand()

    class InvokeSosCallback(
        val callbackName: String,
        val argumentsJson: String,
        val spawnAdmission: LuaSpawnAdmission,
    ) : EngineCommand()

    class StartCoroutine(
        val coroutineId: LuaCoroutineId,
        val spawnAdmission: LuaSpawnAdmission,
    ) : EngineCommand()

    class SetResourceContext(val resourceContextJson: String) : EngineCommand()

    class SetProfileGrants(val grantsJson: String) : EngineCommand()

    class InvokeResolver(val invocationJson: String) : EngineCommand()
}
