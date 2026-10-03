package com.wip.common.models

import java.util.regex.Pattern

/**
 * Shared utility for detecting OCR hallucinations, non-text markers,
 * degenerate model repetition loops, and cleaning OCR text.
 */
object OcrTextFilter {

    private val directMatches = setOf(
        "(no text)", "no text", "none", "n/a", "na", "empty", "nothing",
        "no dialogue", "no speech", "no speech bubble", "no speech bubbles",
        "no text detected", "no text found", "no visible text",
        "(nessun testo)", "nessun testo", "nessun dialogo",
        "1", "0", "null", "undefined",
        "[non-text]", "non-text", "[non text]", "non text", "(non-text)", "(non text)",
        "[not text]", "not text", "(not text)",
        "[image]", "[graphic]", "[illustration]", "[drawing]", "[blank]", "[noise]"
    )

    private val hallucinationRegexes = listOf(
        Regex("""(?i)^\s*\[?\s*(?:no[-\s]?text|non[-\s]?text|not[-\s]?text|nessun[-\s]?testo|none|empty|nothing|no[-\s]?dialogue|no[-\s]?speech(?:\s+bubbles?)?|image|graphic|illustration|drawing|blank|noise)\s*\]?\.?\s*$"""),
        Regex("""(?i)\b(?:the\s+image\s+contains\s+no\s+text|image\s+contains\s+no\s+visible\s+text|there\s+is\s+no\s+text\s+in\s+this\s+image|no\s+text\s+(?:found|detected|visible)\s+in\s+the\s+image)\b"""),
        Regex("""(?i)\b(?:the\s+ocr\s+result.*is\s+a\s+hallucination|does\s+not\s+correspond\s+to\s+any\s+content|absence\s+of\s+any\s+visible\s+text)\b"""),
        Regex("""(?i)\b(?:correct\s+ocr\s+output\s+must\s+reflect\s+the\s+absence\s+of|cannot\s+find\s+any\s+text\s+to\s+transcribe|no\s+transcription\s+available)\b""")
    )

    private val tagRegex1 = Regex("(?i)<\\|/?(?:ref|box|det|quad|grounding|image|text)[^>]*\\|>")
    private val tagRegex2 = Regex("(?i)\\b(?:image|figure|table|header|footer|background|watermark)\\s*\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]")
    private val tagRegex3 = Regex("(?i)^\\s*(?:text|balloon|speech|dialogue|caption|title|paragraph|line)\\s*\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]\\s*")
    private val tagRegex4 = Regex("(?i)^\\s*[{(\\[]\\s*(?:speech|sfx|text|balloon|caption|none)\\s*[})\\]]\\s*(?:\\[?\\s*[\\d.]+\\s*,\\s*[\\d.]+\\s*,\\s*[\\d.]+\\s*,\\s*[\\d.]+\\s*\\]?)?\\s*:?\\s*")
    private val tagRegex5 = Regex("(?i)^\\s*\\[?\\s*[\\d.]+\\s*,\\s*[\\d.]+\\s*,\\s*[\\d.]+\\s*,\\s*[\\d.]+\\s*\\]?\\s*(?:[{(\\[]\\s*(?:speech|sfx|text|balloon|caption|none)\\s*[})\\]])?\\s*:?\\s*")
    private val tagRegex6 = Regex("(?i)^\\s*[{(\\[]\\s*(?:speech|sfx|text|balloon|caption|none)\\s*[})\\]]\\s*:?\\s*")

    fun cleanExtractedText(raw: String): String {
        var text = raw
            .replace(tagRegex1, "")
            .replace(tagRegex2, "")
            .replace(tagRegex3, "")
            .replace(tagRegex4, "")
            .replace(tagRegex5, "")
            .replace(tagRegex6, "")
            .trim()
        if (text.startsWith(":") || text.startsWith("-")) {
            text = text.substring(1).trim()
        }
        return text
    }

    /**
     * Determines whether the given text is valid comic dialogue punctuation (e.g. "?!", "...", "!", "?", "—").
     */
    fun isValidComicPunctuation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val comicPuncChars = setOf('?', '!', '…', '—', '-', '~', '.')
        if (trimmed.all { it in comicPuncChars || it.isWhitespace() }) {
            // Disallow single punctuation noise like ".", ",", ":", ";", "-", "--"
            if (trimmed in setOf(".", ",", ":", ";", "-", "--", "~")) {
                return false
            }
            // Allow dialogue punctuation like "?", "!", "?!", "!?", "...", "…", "—", etc.
            return trimmed.any { it == '?' || it == '!' || it == '…' || it == '—' } || trimmed.count { it == '.' } >= 2
        }
        return false
    }

    fun isHallucinationOrEmpty(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return true
        val clean = cleanExtractedText(rawText.trim())
        if (clean.isBlank()) return true

        if (isDegenerateRepetition(clean)) {
            return true
        }

        if (!clean.any { it.isLetterOrDigit() }) {
            if (clean.length <= 7 && isValidComicPunctuation(clean)) {
                return false
            }
            return true
        }

        val lower = clean.lowercase().trim()
        if (lower in directMatches) return true

        for (regex in hallucinationRegexes) {
            if (regex.containsMatchIn(lower)) {
                return true
            }
        }

        return false
    }

    fun isDegenerateRepetition(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 8) return false

        // 1. Repeating pattern (1-8 chars repeating 5+ total times, e.g. "1.", "abc", "!?", etc.)
        val repMatcher = Pattern.compile("""(.{1,8}?)\1{4,}""", Pattern.DOTALL).matcher(clean)
        if (repMatcher.find()) {
            val repeatedUnit = repMatcher.group(1)
            // If the repeated unit has no letters, it's definitely degenerate (e.g. "1.", "...", "-_-")
            if (!repeatedUnit.any { it.isLetter() }) {
                return true
            }
            // If it has letters, ignore short comic laughter/onomatopoeia unless excessively long
            val isLaughter = setOf("ha", "he", "hi", "ho", "fu", "ku", "ah", "oh", "wa", "ga", "ra", "z")
                .contains(repeatedUnit.lowercase())
            if (!isLaughter || clean.length > 50) {
                return true
            }
        }

        // 2. String without any letters repeating mostly digits or punctuation (e.g. "1.1.1.1.1..." or "1111111111")
        if (!clean.any { it.isLetter() }) {
            val digits = clean.filter { it.isDigit() }
            if (digits.length >= 8 && digits.toSet().size <= 2) {
                return true
            }
        }

        // 3. Token-level repetition: e.g. "word word word word word word word"
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size >= 6 && words.distinctBy { it.lowercase() }.size <= 2) {
            return true
        }

        return false
    }
}
