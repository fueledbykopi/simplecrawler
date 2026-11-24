package com.pocketcrawler.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun `blank or punctuation-only input returns null`() {
        assertNull(FtsQueryBuilder.build(""))
        assertNull(FtsQueryBuilder.build("   "))
        assertNull(FtsQueryBuilder.build("*** ::: \"\"\""))
    }

    @Test
    fun `standard keywords compile to prefix-matched FTS5 terms`() {
        assertEquals(
            "\"books\"* \"travel\"*",
            FtsQueryBuilder.build("books travel")
        )
    }

    @Test
    fun `exact quoted phrase preserves spaces without wildcard`() {
        assertEquals(
            "\"light in the attic\" \"poetry\"*",
            FtsQueryBuilder.build("\"light in the attic\" poetry")
        )
    }

    @Test
    fun `boolean operators and negation shorthand compile safely`() {
        assertEquals(
            "\"fiction\"* OR \"poetry\"* NOT \"mystery\"*",
            FtsQueryBuilder.build("fiction OR poetry -mystery")
        )
    }

    @Test
    fun `leading or dangling operators are stripped cleanly`() {
        assertEquals(
            "\"android\"*",
            FtsQueryBuilder.build("OR AND NOT android OR")
        )
    }

    @Test
    fun `column prefixes map aliases to pages FTS5 columns`() {
        assertEquals(
            "title:\"travel\"* content:\"adventure\"*",
            FtsQueryBuilder.build("title:travel body:adventure")
        )
    }

    @Test
    fun `extractHighlightTerms ignores negated terms and short tokens`() {
        assertEquals(
            listOf("poetry", "light in the attic"),
            FtsQueryBuilder.extractHighlightTerms("title:Poetry \"Light in the Attic\" -mystery a")
        )
    }
}
