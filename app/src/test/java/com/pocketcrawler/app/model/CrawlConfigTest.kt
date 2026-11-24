package com.pocketcrawler.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrawlConfigTest {

    @Test
    fun `sanitizeMaxPagesInput keeps only digits up to max length`() {
        assertEquals("2500", CrawlConfig.sanitizeMaxPagesInput("2,500 pages"))
        assertEquals("1234567", CrawlConfig.sanitizeMaxPagesInput("1234567890"))
        assertEquals("", CrawlConfig.sanitizeMaxPagesInput("abc"))
    }

    @Test
    fun `parseCustomMaxPages supports arbitrary positive numbers including above slider max`() {
        assertEquals(1, CrawlConfig.parseCustomMaxPages("1"))
        assertEquals(250, CrawlConfig.parseCustomMaxPages("250"))
        assertEquals(5000, CrawlConfig.parseCustomMaxPages("5000"))
        assertEquals(100000, CrawlConfig.parseCustomMaxPages("100000"))
    }

    @Test
    fun `parseCustomMaxPages rejects empty or zero values`() {
        assertNull(CrawlConfig.parseCustomMaxPages(""))
        assertNull(CrawlConfig.parseCustomMaxPages("0"))
        assertNull(CrawlConfig.parseCustomMaxPages("000"))
    }
}
