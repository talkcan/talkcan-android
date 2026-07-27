package io.talkcan.lua.kernel

/** Enforces exclusive access to one Lua state from its owning engine thread. */
internal class LuaStateThreadGuard private constructor(
    private val ownerThreadId: Long,
    private val ownerThreadName: String,
) {
    fun checkOwner() {
        val current = Thread.currentThread()
        check(current.id == ownerThreadId) {
            "Lua state owned by $ownerThreadName/$ownerThreadId was accessed from " +
                "${current.name}/${current.id}"
        }
    }

    companion object {
        fun capture(): LuaStateThreadGuard {
            val current = Thread.currentThread()
            return LuaStateThreadGuard(
                ownerThreadId = current.id,
                ownerThreadName = current.name,
            )
        }
    }
}
