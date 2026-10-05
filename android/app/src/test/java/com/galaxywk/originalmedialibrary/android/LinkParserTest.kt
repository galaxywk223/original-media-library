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

    @Test fun parsesNestedDetailResponseWithoutCallingNetwork() {
        val json = """{"data":{"aweme_detail":{"aweme_id":"123","desc":"测试作品","author":{"nickname":"作者"},"video":{"play_addr":{"url_list":["https://cdn.test/video.mp4"]}}}}}"""
        val work = WorkDetailParser.parse(json, "https://www.douyin.com/video/123")
        assertEquals("123", work.awemeId)
        assertEquals("测试作品", work.title)
        assertEquals("video", work.assets.single().kind)
    }

    @Test fun rejectsUnsupportedHostsBeforeWebView() {
        assertTrue(!LinkParser.supported("https://example.com/video/123"))
        assertTrue(LinkParser.extract("https://www.douyin.com/video/123").isNotEmpty())
    }
}
