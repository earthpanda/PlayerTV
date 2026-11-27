package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.ReferenceKind
import com.ai.playtv.model.bean.domain.source.SourceKind
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class SourceRulesTest {
    @Test fun separatesHlsFromChannelListRegardlessOfExtension() {
        assertEquals(SourceKind.HLS, SourceRules.kind("#EXTM3U\n#EXT-X-TARGETDURATION:6\nsegment.ts"))
        assertEquals(SourceKind.UNKNOWN, SourceRules.kind("#EXTM3U\n#EXTINF:-1,News\nhttps://tv.example/a.m3u8"))
        assertEquals(SourceKind.DASH, SourceRules.kind("<?xml version=\"1.0\"?><MPD type=\"dynamic\">"))
    }
    @Test fun parsesRelativeAndTxtItemsAndReportsUnsupportedSchemes() {
        val parsed = SourceRules.playlist(URI("https://example.org/lists/channels.m3u"),
            "\uFEFF\n#EXTM3U\n#EXTINF:-1,News\n../live.m3u8\n分类,#genre#\n电影|First,https://cdn.example/movie.m3u8\nOld,http://example.org/old\n")
        assertEquals(listOf("News", "电影|First"), parsed.items.map { it.name })
        assertEquals("https://example.org/live.m3u8", parsed.items[0].url)
        assertTrue(parsed.skipped)
    }
    @Test fun findsGithubAttachmentsButKeepsCloudSharesSeparate() {
        val text = "[demo](https://github.com/user-attachments/assets/abc) [file](https://example.org/a.mp4) [share](https://pan.quark.cn/s/abc)"
        assertEquals(2, SourceRules.markdownLinks(text).size)
        assertTrue(ReferenceKind.CLOUD_SHARE in SourceRules.referenceKinds(text))
        assertEquals(listOf("https://example.org/docs/demo.mp4"), SourceRules.markdownLinks("[demo](docs/demo.mp4)", URI("https://example.org/README.md")))
    }
    @Test fun rejectsCredentialsCleartextAndFragments() {
        listOf("http://example.org/a", "https://name:password@example.org/a", "https://example.org/a#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { SourceRules.checkedUrl(it) }
        }
    }
    @Test fun transportStreamRequiresPacketSyncInsteadOfTsExtension() {
        assertFalse(SourceRules.isTransportStream("export const video = true".toByteArray()))
        val packets = ByteArray(564)
        packets[0] = 0x47; packets[188] = 0x47; packets[376] = 0x47
        assertTrue(SourceRules.isTransportStream(packets))
    }

    @Test fun playlistPreservesValidArtworkMetadataWithoutLeakingToNextItem() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="sample.id" tvg-logo="../logo.png?x=1,2",Example
            https://example.org/one.m3u8
            #EXTINF:-1 tvg-id='second.id' tvg-logo='http://example.org/logo.png',Second
            https://example.org/two.m3u8
            https://example.org/three.m3u8
        """.trimIndent()
        val items = SourceRules.playlist(URI("https://example.org/list/channels.m3u"), text).items
        assertEquals(listOf("Example", "Second", "three.m3u8"), items.map { it.name })
        assertEquals("sample.id", items[0].channelId)
        assertEquals("https://example.org/logo.png?x=1,2", items[0].logoUrl)
        assertEquals("second.id", items[1].channelId)
        assertNull(items[1].logoUrl)
        assertNull(items[2].channelId)
        assertNull(items[2].logoUrl)
    }

    @Test fun duplicateChannelCanSupplyMissingLogoWithoutChangingItsOrder() {
        val text = """
            #EXTM3U
            #EXTINF:-1,First name
            https://example.org/live.m3u8
            #EXTINF:-1 tvg-logo="logo.png",Second name
            https://example.org/live.m3u8
        """.trimIndent()
        val items = SourceRules.playlist(URI("https://example.org/list.m3u"), text).items
        assertEquals(1, items.size)
        assertEquals("First name", items.single().name)
        assertEquals("https://example.org/logo.png", items.single().logoUrl)
    }
}
