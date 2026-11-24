package com.pocketcrawler.app.data.search

/**
 * Compiles user search text into a safe SQLite FTS5 `MATCH` expression against
 * the `pages(url UNINDEXED, title, content)` virtual table.
 *
 * Supported syntax:
 * - Keywords: `python travel` -> `"python"* "travel"*`
 * - Exact phrases: `"light in the attic"` -> `"light in the attic"`
 * - Boolean operators: `fiction OR poetry`, `book NOT mystery`, `-mystery`
 * - Column filters: `title:travel`, `content:adventure`
 */
object FtsQueryBuilder {

    const val HIGHLIGHT_START = "\u00AB" // «
    const val HIGHLIGHT_END = "\u00BB"   // »

    private val COLUMN_ALIASES = mapOf(
        "title" to "title",
        "t" to "title",
        "content" to "content",
        "body" to "content",
        "text" to "content"
    )

    private val BOOLEAN_OPERATORS = setOf("AND", "OR", "NOT")

    fun build(rawInput: String): String? {
        val tokens = tokenize(rawInput)
        if (tokens.isEmpty()) return null

        val output = mutableListOf<String>()
        var lastWasOperator = true

        for (token in tokens) {
            when (token) {
                is RawToken.Operator -> {
                    if (!lastWasOperator && output.isNotEmpty()) {
                        output.add(token.op)
                        lastWasOperator = true
                    }
                }

                is RawToken.Term -> {
                    val expr = formatTerm(token) ?: continue
                    if (token.negated) {
                        if (output.isNotEmpty() && !lastWasOperator) {
                            output.add("NOT")
                            output.add(expr)
                            lastWasOperator = false
                        }
                    } else {
                        output.add(expr)
                        lastWasOperator = false
                    }
                }
            }
        }

        while (output.isNotEmpty() && output.last() in BOOLEAN_OPERATORS) {
            output.removeAt(output.lastIndex)
        }

        return if (output.isEmpty()) null else output.joinToString(" ")
    }

    fun extractHighlightTerms(rawInput: String): List<String> {
        return tokenize(rawInput)
            .filterIsInstance<RawToken.Term>()
            .filter { !it.negated && it.text.length >= 2 }
            .map { it.text.trim().lowercase() }
            .distinct()
    }

    private fun formatTerm(token: RawToken.Term): String? {
        val cleaned = token.text.replace("\"", "").trim()
        if (cleaned.isEmpty() || cleaned.none { it.isLetterOrDigit() }) return null

        val quoted = "\"$cleaned\""
        val withPrefix = if (!token.isPhrase && cleaned.length >= 2 && cleaned.last().isLetterOrDigit()) {
            "$quoted*"
        } else {
            quoted
        }

        val mappedColumn = token.column?.lowercase()?.let { COLUMN_ALIASES[it] }
        return if (mappedColumn != null) "$mappedColumn:$withPrefix" else withPrefix
    }

    private sealed class RawToken {
        data class Operator(val op: String) : RawToken()
        data class Term(
            val text: String,
            val isPhrase: Boolean,
            val column: String? = null,
            val negated: Boolean = false
        ) : RawToken()
    }

    private fun tokenize(input: String): List<RawToken> {
        val result = mutableListOf<RawToken>()
        val length = input.length
        var i = 0

        while (i < length) {
            while (i < length && input[i].isWhitespace()) i++
            if (i >= length) break

            var negated = false
            if (input[i] == '-' && i + 1 < length && !input[i + 1].isWhitespace()) {
                negated = true
                i++
            }

            var column: String? = null
            val colonIdx = findColumnPrefix(input, i)
            if (colonIdx != -1) {
                val candidate = input.substring(i, colonIdx)
                if (candidate.lowercase() in COLUMN_ALIASES) {
                    column = candidate
                    i = colonIdx + 1
                }
            }

            if (i >= length) break

            if (input[i] == '"') {
                i++
                val start = i
                while (i < length && input[i] != '"') i++
                val phrase = input.substring(start, i).trim()
                if (i < length && input[i] == '"') i++
                if (phrase.isNotEmpty()) {
                    result.add(RawToken.Term(phrase, isPhrase = true, column = column, negated = negated))
                }
            } else {
                val start = i
                while (i < length && !input[i].isWhitespace()) i++
                val word = input.substring(start, i)
                if (!negated && column == null && word in BOOLEAN_OPERATORS) {
                    result.add(RawToken.Operator(word))
                } else {
                    result.add(RawToken.Term(word, isPhrase = false, column = column, negated = negated))
                }
            }
        }
        return result
    }

    private fun findColumnPrefix(input: String, start: Int): Int {
        var j = start
        while (j < input.length && input[j].isLetter()) j++
        return if (j > start && j < input.length && input[j] == ':' && j + 1 < input.length && !input[j + 1].isWhitespace()) {
            j
        } else {
            -1
        }
    }
}
