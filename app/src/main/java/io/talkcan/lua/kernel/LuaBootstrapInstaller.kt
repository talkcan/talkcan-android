package io.talkcan.lua.kernel

import java.nio.charset.StandardCharsets
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.lua54.Lua54

/** Installs the trusted, resource-backed Talkcan sandbox on the owner thread. */
internal object LuaBootstrapInstaller {
    private const val RESOURCE = "/io/talkcan/lua/kernel/bootstrap.lua"

    fun install(lua: Lua54, state: LuaEngineState, runtime: LuaEngineRuntime) {
        val source = LuaBootstrapInstaller::class.java.getResourceAsStream(RESOURCE)?.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        } ?: error("trusted Lua bootstrap resource is missing: $RESOURCE")

        lua.set(
            "__talkcan_watchdog_tripped",
            JFunction { callbackState ->
                callbackState.push(state.interruptRequested())
                1
            },
        )
        lua.newTable()
        val talkcanIdx = lua.getTop()
        lua.push(runtime.hostHashCallback())
        lua.setField(talkcanIdx, "host_hash")
        lua.push(runtime.hostCallCallback())
        lua.setField(talkcanIdx, "host_call")
        lua.push(runtime.hostSpawnCallback())
        lua.setField(talkcanIdx, "host_spawn")
        lua.push(runtime.hostDeferCallback())
        lua.setField(talkcanIdx, "host_defer")
        lua.push(runtime.hostPrepareSleepCallback())
        lua.push(runtime.hostInstanceIdCallback())
        lua.setField(talkcanIdx, "host_instance_id")
        lua.push(runtime.hostAcknowledgeSpawnContextCallback())
        lua.setField(talkcanIdx, "host_acknowledge_spawn_context")
        lua.setField(talkcanIdx, "host_prepare_sleep")
        lua.push(runtime.hostFsMountCallback())
        lua.setField(talkcanIdx, "host_fs_mount")
        lua.push(runtime.hostProfilesGetCallback())
        lua.setField(talkcanIdx, "host_profiles_get")
        lua.push(runtime.hostWorkOpenCallback())
        lua.setField(talkcanIdx, "host_work_open")
        lua.push(runtime.hostAudioDescribeCallback())
        lua.setField(talkcanIdx, "host_audio_describe")
        lua.push(runtime.hostJsonEncodeCallback())
        lua.setField(talkcanIdx, "host_json_encode")
        lua.push(runtime.hostJsonDecodeCallback())
        lua.setField(talkcanIdx, "host_json_decode")
        lua.push(runtime.hostOpaqueKindCallback())
        lua.setField(talkcanIdx, "host_opaque_kind")
        lua.push(runtime.hostWorkJobPayloadCallback())
        lua.setField(talkcanIdx, "host_work_job_payload")
        lua.push(runtime.hostLogCallback())
        lua.setField(talkcanIdx, "host_log")
        for ((name, callback) in runtime.hostRequestCallbacks()) {
            lua.push(callback)
            lua.setField(talkcanIdx, name)
        }
        lua.setGlobal("talkcan")
        lua.set("__talkcan_hook_interval", state.config.hookInterval)
        lua.set("__talkcan_instruction_budget", state.config.instructionBudget)
        lua.run(source)
    }
}
