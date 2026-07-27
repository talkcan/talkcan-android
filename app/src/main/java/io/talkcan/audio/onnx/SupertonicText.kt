/*
 * Portions adapted from the official Supertonic Java reference, revision
 * dff55dc00064c398736080c78195f577527832ae.
 * Copyright (c) 2025 Supertone Inc.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.talkcan.audio.onnx

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Raised when text is tagged with a language outside the supported TTS set.
 * The message names the rejected language and lists the supported set.
 */
internal class SupertonicLanguageException(message: String) : RuntimeException(message)

/** Languages accepted by the Supertonic multilingual TTS pipeline. */
internal object SupertonicLanguages {
    /** Full supported set, in reference order. */
    val AVAILABLE: List<String> =
        listOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi",
            "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro",
            "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
        )

    private val AVAILABLE_SET: Set<String> = AVAILABLE.toHashSet()

    fun isValid(language: String): Boolean = language in AVAILABLE_SET
}

/**
 * Pure text pipeline shared by the Supertonic engine: normalization and
 * symbol cleanup, terminal punctuation, language tagging, abbreviation-aware
 * sentence splitting, and language-dependent chunking.
 *
 * Adapted from the reference `UnicodeProcessor.preprocessText` and
 * `Helper.chunkText`/`Helper.splitSentences`. All lengths are UTF-16 char
 * counts, matching Java `String.length`. Source divergences from the pinned
 * Java reference:
 * - symbol/expression replacements apply in the pinned order below; the
 *   reference iterated a `HashMap` with unspecified order;
 * - language validation fails fast before text work instead of after it;
 * - `chunkText` rejects negative limits instead of looping on them;
 * - empty text still gains the terminal period, matching the reference
 *   (the Rust adaptation skips the period on empty text).
 */
internal object SupertonicText {
    /** Dash, quote, bracket, and symbol replacements applied in pinned order. */
    private val REPLACEMENTS: Array<Pair<String, String>> =
        arrayOf(
            "\u2013" to "-", // en dash
            "\u2011" to "-", // non-breaking hyphen
            "\u2014" to "-", // em dash
            "_" to " ",
            "\u201C" to "\"", // left double quote
            "\u201D" to "\"", // right double quote
            "\u2018" to "'", // left single quote
            "\u2019" to "'", // right single quote
            "\u00B4" to "'", // acute accent
            "`" to "'", // grave accent
            "[" to " ",
            "]" to " ",
            "|" to " ",
            "/" to " ",
            "#" to " ",
            "\u2192" to " ", // rightwards arrow
            "\u2190" to " ", // leftwards arrow
        )

    /** Known-expression expansions applied after symbol replacement. */
    private val EXPRESSION_REPLACEMENTS: Array<Pair<String, String>> =
        arrayOf(
            "@" to " at ",
            "e.g.," to "for example, ",
            "i.e.," to "that is, ",
        )

    private val SPECIAL_SYMBOLS = Regex("[♥☆♡©\\\\]")

    private val PUNCTUATION_SPACING_FIXES: Array<Pair<Regex, String>> =
        arrayOf(
            Regex(" ,") to ",",
            Regex(" \\.") to ".",
            Regex(" !") to "!",
            Regex(" \\?") to "?",
            Regex(" ;") to ";",
            Regex(" :") to ":",
            Regex(" '") to "'",
        )

    private val WHITESPACE_RUN = Regex("\\s+")

    private val TERMINAL_PUNCTUATION =
        Regex(".*[.!?;:,'\"\u201C\u201D\u2018\u2019)\\]}…。」』】〉》›»]$")

    /** Abbreviations that must not end a sentence split. */
    private val ABBREVIATIONS: Array<String> =
        arrayOf(
            "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
            "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
            "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D.",
        )

    private val PARAGRAPH_BOUNDARY = Regex("\\n\\s*\\n")
    private val WORD_BOUNDARY = Regex("\\s+")

    private val SENTENCE_BOUNDARY: Regex by lazy {
        val quoted = ABBREVIATIONS.joinToString(separator = "|") { Pattern.quote(it) }
        Regex("(?<!(?:$quoted))(?<=[.!?])\\s+")
    }

    /**
     * Normalizes [text] for the text encoder and wraps it in [language] tags.
     *
     * Pipeline: NFKD normalization, emoji removal, pinned symbol replacements,
     * special-symbol removal, expression expansion, punctuation-spacing
     * fixes, duplicate-quote compaction, whitespace collapse and trim,
     * terminal-period enforcement, then `<lang>...</lang>` wrapping.
     *
     * @throws SupertonicLanguageException if [language] is unsupported.
     */
    fun preprocessText(text: String, language: String): String {
        if (!SupertonicLanguages.isValid(language)) {
            throw SupertonicLanguageException(
                "Unsupported language '$language'. Available: ${SupertonicLanguages.AVAILABLE}"
            )
        }
        var current = Normalizer.normalize(text, Normalizer.Form.NFKD)
        current = removeEmojis(current)
        for ((from, to) in REPLACEMENTS) {
            current = current.replace(from, to)
        }
        current = SPECIAL_SYMBOLS.replace(current, "")
        for ((from, to) in EXPRESSION_REPLACEMENTS) {
            current = current.replace(from, to)
        }
        for ((pattern, replacement) in PUNCTUATION_SPACING_FIXES) {
            current = pattern.replace(current, replacement)
        }
        while (current.contains("\"\"")) {
            current = current.replace("\"\"", "\"")
        }
        while (current.contains("''")) {
            current = current.replace("''", "'")
        }
        while (current.contains("``")) {
            current = current.replace("``", "`")
        }
        current = WHITESPACE_RUN.replace(current, " ").trim()
        if (!TERMINAL_PUNCTUATION.matches(current)) {
            current += "."
        }
        return "<$language>$current</$language>"
    }

    /**
     * Splits [text] into sentences at `[.!?]` followed by whitespace, without
     * splitting after a known abbreviation. Matches the reference
     * negative-lookbehind pattern over `Pattern.quote`d abbreviations.
     */
    fun splitSentences(text: String): List<String> = SENTENCE_BOUNDARY.split(text)

    /**
     * Per-language chunk limit from the reference `TextToSpeech.call`:
     * 120 chars for `ko`/`ja`, 300 otherwise.
     */
    fun maxChunkLength(language: String): Int =
        if (language == "ko" || language == "ja") {
            SupertonicOnnxContract.CJK_MAX_CHUNK_LENGTH
        } else {
            SupertonicOnnxContract.DEFAULT_MAX_CHUNK_LENGTH
        }

    /**
     * Chunks [text] by paragraph, then sentence, then comma, then word
     * fallback, mirroring the reference `Helper.chunkText`. A [maxLen] of 0
     * selects [SupertonicOnnxContract.DEFAULT_MAX_CHUNK_LENGTH]; negative
     * limits are rejected. Blank input yields a single empty chunk.
     */
    fun chunkText(text: String, maxLen: Int = 0): List<String> {
        require(maxLen >= 0) { "maxLen must be >= 0, found $maxLen" }
        val limit = if (maxLen == 0) SupertonicOnnxContract.DEFAULT_MAX_CHUNK_LENGTH else maxLen
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return listOf("")
        }

        val chunks = ArrayList<String>()
        for (rawParagraph in PARAGRAPH_BOUNDARY.split(trimmed)) {
            val paragraph = rawParagraph.trim()
            if (paragraph.isEmpty()) {
                continue
            }
            if (paragraph.length <= limit) {
                chunks.add(paragraph)
                continue
            }
            chunkParagraph(paragraph, limit, chunks)
        }
        return if (chunks.isEmpty()) listOf("") else chunks
    }

    private fun chunkParagraph(paragraph: String, limit: Int, chunks: MutableList<String>) {
        val current = StringBuilder()
        var currentLen = 0

        for (rawSentence in splitSentences(paragraph)) {
            val sentence = rawSentence.trim()
            if (sentence.isEmpty()) {
                continue
            }

            if (sentence.length > limit) {
                // Long sentence: flush the accumulator, then fall back to comma/word splits.
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.setLength(0)
                    currentLen = 0
                }
                for (rawPart in sentence.split(",")) {
                    val part = rawPart.trim()
                    if (part.isEmpty()) {
                        continue
                    }
                    if (part.length > limit) {
                        addWordChunks(part, limit, chunks)
                    } else {
                        if (currentLen + part.length + 1 > limit && current.isNotEmpty()) {
                            chunks.add(current.toString().trim())
                            current.setLength(0)
                            currentLen = 0
                        }
                        if (current.isNotEmpty()) {
                            current.append(", ")
                            currentLen += 2
                        }
                        current.append(part)
                        currentLen += part.length
                    }
                }
                continue
            }

            if (currentLen + sentence.length + 1 > limit && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.setLength(0)
                currentLen = 0
            }
            if (current.isNotEmpty()) {
                current.append(' ')
                currentLen++
            }
            current.append(sentence)
            currentLen += sentence.length
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }
    }

    private fun addWordChunks(part: String, limit: Int, chunks: MutableList<String>) {
        val wordChunk = StringBuilder()
        var wordChunkLen = 0
        for (word in WORD_BOUNDARY.split(part)) {
            if (wordChunkLen + word.length + 1 > limit && wordChunk.isNotEmpty()) {
                chunks.add(wordChunk.toString().trim())
                wordChunk.setLength(0)
                wordChunkLen = 0
            }
            if (wordChunk.isNotEmpty()) {
                wordChunk.append(' ')
                wordChunkLen++
            }
            wordChunk.append(word)
            wordChunkLen += word.length
        }
        if (wordChunk.isNotEmpty()) {
            chunks.add(wordChunk.toString().trim())
        }
    }

    /** Code-point scan over the reference emoji ranges; surrogate-pair aware. */
    private fun removeEmojis(text: String): String {
        val result = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val codePoint: Int
            if (
                text[i].isHighSurrogate() &&
                i + 1 < text.length &&
                text[i + 1].isLowSurrogate()
            ) {
                codePoint = Character.codePointAt(text, i)
                i++ // skip the low surrogate
            } else {
                codePoint = text[i].code
            }
            i++
            if (!isEmojiCodePoint(codePoint)) {
                result.appendCodePoint(codePoint)
            }
        }
        return result.toString()
    }

    private fun isEmojiCodePoint(codePoint: Int): Boolean =
        (codePoint in 0x1F600..0x1F64F) ||
            (codePoint in 0x1F300..0x1F5FF) ||
            (codePoint in 0x1F680..0x1F6FF) ||
            (codePoint in 0x1F700..0x1F77F) ||
            (codePoint in 0x1F780..0x1F7FF) ||
            (codePoint in 0x1F800..0x1F8FF) ||
            (codePoint in 0x1F900..0x1F9FF) ||
            (codePoint in 0x1FA00..0x1FA6F) ||
            (codePoint in 0x1FA70..0x1FAFF) ||
            (codePoint in 0x2600..0x26FF) ||
            (codePoint in 0x2700..0x27BF) ||
            (codePoint in 0x1F1E6..0x1F1FF)
}
