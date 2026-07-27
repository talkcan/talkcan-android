package io.talkcan.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Numeric subtype contract for the project <-> Lua value boundary. Lua 5.4
 * integer and float subtypes must survive JSON parsing, encoding, and
 * round trips exactly as under the deleted Rust kernel, whose `Value::Integer`
 * and `Value::Number` variants this model mirrors.
 */
internal class LuaValueNumericContractTest {

    private fun parse(json: String): LuaValue =
        when (val result = LuaValue.fromJsonString(json)) {
            is JsonParsingResult.Success -> result.value
            is JsonParsingResult.Failure -> throw AssertionError("expected successful parse of $json: ${result.diagnostic}")
        }

    private fun encode(value: LuaValue): String =
        when (val result = value.toJsonString()) {
            is JsonEncodingResult.Success -> result.json
            is JsonEncodingResult.Failure -> throw AssertionError("expected successful encoding of $value: ${result.diagnostic}")
        }

    @Test
    fun json_to_lua_preserves_mathematical_integers() {
        // Port of the deleted Rust `json_to_lua_preserves_mathematical_integers`:
        // integer lexemes arrive as signed 64-bit integers, fractional lexemes
        // as floats.
        assertEquals(LuaValue.Integer(1784796437093L), parse("1784796437093"))
        assertEquals(LuaValue.Real(1.5), parse("1.5"))
        assertEquals(LuaValue.Integer(0L), parse("0"))
        assertEquals(LuaValue.Integer(-7L), parse("-7"))
    }

    @Test
    fun signed_64_bit_boundaries_round_trip_exactly() {
        for (boundary in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val value = LuaValue.Integer(boundary)
            val json = encode(value)
            assertEquals(boundary.toString(), json)
            assertEquals(value, parse(json))
        }
    }

    @Test
    fun integer_json_round_trips_without_precision_loss() {
        // 2^53 + 1 is the first magnitude a Double cannot represent; the former
        // Double-only model rounded it to 9007199254740992.
        val value = LuaValue.Integer(9_007_199_254_740_993L)
        assertEquals("9007199254740993", encode(value))
        assertEquals(value, parse(encode(value)))
    }

    @Test
    fun integral_reals_remain_lua_floats() {
        assertEquals(LuaValue.Real(1.0), parse("1.0"))
        assertEquals(LuaValue.Real(16_000.0), parse("16000.0"))
        assertEquals(LuaValue.Real(1000.0), parse("1e3"))
        // The float subtype must survive an encode/decode round trip so a
        // mathematical-integer float never silently becomes an integer.
        assertEquals(LuaValue.Real(1.0), parse(encode(LuaValue.Real(1.0))))
        assertEquals("1.0", encode(LuaValue.Real(1.0)))
        assertEquals("-0.0", encode(LuaValue.Real(-0.0)))
    }

    @Test
    fun integer_lexemes_beyond_signed_64_bit_range_fall_back_to_floats() {
        // Mirrors the Rust serde path: integer tokens outside i64 become f64.
        assertEquals(LuaValue.Real(9.223372036854776E18), parse("9223372036854775808"))
        assertEquals(LuaValue.Real(-9.223372036854776E18), parse("-9223372036854775809"))
    }

    @Test
    fun integer_lexemes_encode_without_fractional_suffix() {
        assertEquals("200", encode(LuaValue.Integer(200L)))
        assertEquals("-1", encode(LuaValue.Integer(-1L)))
        assertEquals("200", encode(parse("200")))
    }

    @Test
    fun non_finite_numbers_remain_rejected() {
        val infinity = LuaValue.Real(Double.POSITIVE_INFINITY).toJsonString()
        assertTrue("non-finite encoding must fail: $infinity", infinity is JsonEncodingResult.Failure)
        assertTrue(
            "the finite-number diagnostic must survive the subtype split: $infinity",
            (infinity as JsonEncodingResult.Failure).diagnostic.contains("number must be finite"),
        )
        val nan = LuaValue.Real(Double.NaN).toJsonString()
        assertTrue("NaN encoding must fail: $nan", nan is JsonEncodingResult.Failure)
        assertTrue(LuaValue.fromJsonString("1e999") is JsonParsingResult.Failure)
    }
}
