package io.talkcan.protocol

import io.talkcan.model.RawButtonEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonParserTest {
    @Test
    fun parsesSingleToken() {
        val parser = ButtonParser()

        assertEquals(
            listOf(RawButtonEvent.PttPressed),
            parser.push("+PTT=P".encodeToByteArray()),
        )
    }

    @Test
    fun parsesConcatenatedTokens() {
        val parser = ButtonParser()

        assertEquals(
            listOf(RawButtonEvent.PttPressed, RawButtonEvent.PttReleased),
            parser.push("+PTT=P+PTT=R".encodeToByteArray()),
        )
    }

    @Test
    fun retainsSplitToken() {
        val parser = ButtonParser()

        assertEquals(emptyList<RawButtonEvent>(), parser.push("+PT".encodeToByteArray()))
        assertEquals(
            listOf(RawButtonEvent.PttPressed),
            parser.push("T=P".encodeToByteArray()),
        )
    }

    @Test
    fun ignoresOptionalNulAfterToken() {
        val parser = ButtonParser()

        assertEquals(
            listOf(RawButtonEvent.SosPressed, RawButtonEvent.GroupPressed),
            parser.push(byteArrayOf('C'.code.toByte(), ':'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '*'.code.toByte(), 0) +
                "C:GP*".encodeToByteArray()),
        )
    }

    @Test
    fun discardsNoiseBeforeKnownToken() {
        val parser = ButtonParser()

        assertEquals(
            listOf(RawButtonEvent.VolumeUpClicked),
            parser.push("noiseC:VM*".encodeToByteArray()),
        )
    }

    @Test
    fun mapsVpTokenToVolumeDown() {
        val parser = ButtonParser()

        assertEquals(
            listOf(RawButtonEvent.VolumeDownClicked),
            parser.push("C:VP*".encodeToByteArray()),
        )
    }

    @Test
    fun retainsPartialSuffixAfterNoise() {
        val parser = ButtonParser()

        assertEquals(emptyList<RawButtonEvent>(), parser.push("noise+P".encodeToByteArray()))
        assertEquals(
            listOf(RawButtonEvent.PttReleased),
            parser.push("TT=R".encodeToByteArray()),
        )
    }
}
