package com.galaxywk.originalmedialibrary.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3ArgumentsTest {
    @Test fun preservesMp3ExtensionAndPassesPathsAsSeparateArguments() {
        val input = File("/media/中文 #作品/video.mp4")
        val output = File("/media/中文 #作品/video.mp3")
        val temporary = Mp3Arguments.temporary(output)
        val args = Mp3Arguments.command(input, temporary).toList()
        assertEquals("video.part.mp3", temporary.name)
        assertEquals(input.absolutePath, args[args.indexOf("-i") + 1])
        assertEquals("192k", args[args.indexOf("-b:a") + 1])
        assertEquals("libmp3lame", args[args.indexOf("-codec:a") + 1])
        assertEquals("mp3", args[args.indexOf("-f") + 1])
        assertTrue(args.last().endsWith(".part.mp3"))
    }
}
