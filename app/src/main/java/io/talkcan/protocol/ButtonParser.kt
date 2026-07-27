package io.talkcan.protocol

import io.talkcan.model.RawButtonEvent

class ButtonParser {
    private val buffer = StringBuilder()

    fun push(bytes: ByteArray): List<RawButtonEvent> {
        bytes.forEach { buffer.append((it.toInt() and 0xff).toChar()) }

        val events = mutableListOf<RawButtonEvent>()
        while (buffer.isNotEmpty()) {
            val match = tokenAtStart()
            if (match != null) {
                buffer.delete(0, match.token.length)
                if (buffer.firstOrNull() == '\u0000') buffer.deleteCharAt(0)
                events += match.event
                continue
            }

            if (knownTokens.any { it.token.startsWith(buffer.toString()) }) break

            val nextTokenIndex = nextFullTokenIndex()
            if (nextTokenIndex != null) {
                buffer.delete(0, nextTokenIndex)
                continue
            }

            val keep = longestSuffixThatCanStartToken()
            buffer.delete(0, buffer.length - keep)
            break
        }

        return events
    }

    private fun tokenAtStart(): KnownToken? = knownTokens.firstOrNull { buffer.startsWithToken(it.token) }

    private fun nextFullTokenIndex(): Int? = knownTokens
        .map { buffer.indexOf(it.token) }
        .filter { it >= 0 }
        .minOrNull()

    private fun longestSuffixThatCanStartToken(): Int {
        val maxLength = minOf(buffer.length, knownTokens.maxOf { it.token.length } - 1)
        for (length in maxLength downTo 1) {
            val suffix = buffer.substring(buffer.length - length)
            if (knownTokens.any { it.token.startsWith(suffix) }) return length
        }
        return 0
    }

    private data class KnownToken(val token: String, val event: RawButtonEvent)

    private fun StringBuilder.startsWithToken(token: String): Boolean =
        length >= token.length && substring(0, token.length) == token

    private companion object {
        val knownTokens = listOf(
            KnownToken("+PTT=P", RawButtonEvent.PttPressed),
            KnownToken("+PTT=R", RawButtonEvent.PttReleased),
            KnownToken("C:SP*", RawButtonEvent.SosPressed),
            KnownToken("C:SR*", RawButtonEvent.SosReleased),
            KnownToken("C:SOS*", RawButtonEvent.SosLongPressed),
            KnownToken("C:GP*", RawButtonEvent.GroupPressed),
            KnownToken("C:GR*", RawButtonEvent.GroupReleased),
            KnownToken("C:VP*", RawButtonEvent.VolumeDownClicked),
            KnownToken("C:VM*", RawButtonEvent.VolumeUpClicked),
        )
    }
}
