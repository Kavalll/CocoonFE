package com.thorfeeds.catalog.core

/**
 * Turns a feed title into the stable name used for catalog identity and
 * on-device matching. The raw title is kept separately for the release row.
 *
 * Rule: cut from the case-insensitive marker "Free Download" through the end
 * of the string, then drop leftover build tags and parenthetical versions.
 */
object TitleCanonicalizer {
    private val freeDownload = Regex("""\s*Free Download.*""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")

    private val decorations = listOf(
        Regex(
            """\s*[(\[]\s*(?:build|patch|update|revision|rev|version|ver|v)\s*\d[^)\]]*[)\]]\s*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\s*[(\[]\s*v?\d+(?:\.\d+){1,4}[^)\]]*[)\]]\s*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\s+build\s+\d+\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+v(?:ersion)?\s*\d+(?:\.\d+)*\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+ver(?:sion)?\s+\d+(?:\.\d+)*\s*$""", RegexOption.IGNORE_CASE),
    )

    fun canonical(raw: String): String {
        val flattened = raw.replace('\u00A0', ' ').replace(Regex("""\s+"""), " ").trim()
        if (flattened.isEmpty()) return "Untitled"
        var working = freeDownload.replace(flattened, "")
        repeat(6) {
            val next = stripOneDecoration(working)
            if (next == working) return@repeat
            working = next
        }
        working = working.trim().trimEnd('-', '–', '—', '|', ':').trim()
        working = whitespace.replace(working, " ").trim()
        return working.ifBlank { flattened }
    }

    fun identityKey(canonicalTitle: String): String = canonicalTitle.trim().lowercase()

    /** Punctuation-insensitive key for folder and file names. */
    fun matchKey(name: String): String {
        return canonical(name).lowercase().replace(Regex("""[^a-z0-9]+"""), "")
    }

    private fun stripOneDecoration(input: String): String {
        var current = input.trim()
        for (pattern in decorations) {
            val stripped = pattern.replace(current, "").trim()
            if (stripped != current) return stripped
        }
        return current
    }
}
