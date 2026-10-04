package com.galaxywk.originalmedialibrary.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    @Test fun extractsUniqueDouyinLinks() {
        val links = LinkParser.extract("看这个 https://www.douyin.com/video/123，重复 https://www.douyin.com/video/123")
        assertEquals(listOf("https://www.douyin.com/video/123"), links)
    }

    @Test fun extractsAwemeIdFromVideoAndNote() {
        assertEquals("123", LinkParser.id("https://www.douyin.com/video/123"))
        assertEquals("456", LinkParser.id("https://www.douyin.com/note/456"))
        assertTrue(LinkParser.id("https://example.com/video/1") == null)
    }
}
