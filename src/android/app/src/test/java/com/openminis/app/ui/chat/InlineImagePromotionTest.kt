package com.openminis.app.ui.chat

import com.openminis.app.text.BoundedText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-inline-image-blue-link] Inline `![alt](url)` images inside a paragraph
 * used to render as a blue `[alt]` link: the paragraph splitter only promoted
 * video/audio extensions to standalone media blocks, so image markdown stayed
 * inside the paragraph where the inline parser has no bitmap slot. The fix
 * promotes image references to standalone [MdBlock.Image] blocks exactly like
 * video/audio — the block renderer already draws them as tappable cards.
 */
class InlineImagePromotionTest {

    @Test
    fun inlineImageInProseBecomesStandaloneImageBlock() = runTest {
        val blocks = parseMarkdownBlocks("look at this ![chart](minis://workspace/chart.png) nice")
        assertEquals(
            listOf(MdBlock.Paragraph::class.java, MdBlock.Image::class.java, MdBlock.Paragraph::class.java),
            blocks.map { it::class.java },
        )
        val img = blocks[1] as MdBlock.Image
        assertEquals("chart", img.alt)
        assertEquals("minis://workspace/chart.png", img.url)
        assertEquals("look at this", (blocks[0] as MdBlock.Paragraph).raw.trim())
        assertEquals("nice", (blocks[2] as MdBlock.Paragraph).raw.trim())
    }

    @Test
    fun inlineImageWithoutExtensionStillPromotes() = runTest {
        // Extension-less URLs (minis://attachments/uploads/69893 style) must
        // promote too — mediaBlockFrom defaults to Image for unknown exts.
        val blocks = parseMarkdownBlocks("before ![x](minis://attachments/uploads/69893) after")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Image)
    }

    @Test
    fun multipleInlineImagesEachBecomeBlocks() = runTest {
        val blocks = parseMarkdownBlocks("a ![1](u1.png) b ![2](u2.png) c")
        assertEquals(
            listOf(
                MdBlock.Paragraph::class.java, MdBlock.Image::class.java,
                MdBlock.Paragraph::class.java, MdBlock.Image::class.java,
                MdBlock.Paragraph::class.java,
            ),
            blocks.map { it::class.java },
        )
    }

    @Test
    fun inlineVideoAndImageMixPromotesBoth() = runTest {
        val blocks = parseMarkdownBlocks("a ![v](clip.mp4) b ![i](pic.png) c")
        assertEquals(
            listOf(
                MdBlock.Paragraph::class.java, MdBlock.Video::class.java,
                MdBlock.Paragraph::class.java, MdBlock.Image::class.java,
                MdBlock.Paragraph::class.java,
            ),
            blocks.map { it::class.java },
        )
    }

    @Test
    fun standaloneImageLineStillSingleBlock() = runTest {
        val blocks = parseMarkdownBlocks("![alt](x.png)")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Image)
    }

    @Test
    fun plainLinkStaysInlineNotImage() = runTest {
        // [text](url) without the bang must NOT be promoted — regression guard
        // for the splitter regex only matching `![`.
        val blocks = parseMarkdownBlocks("see [docs](https://example.com) here")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun oversizedParagraphKeepsImageInlineAsLinkFallback() = runTest {
        // BoundedText.MAX_COMPOSE_BLOCK_CHARS chunking: an oversized paragraph
        // skips the inline-media split (ICU guard) but still chunks into
        // multiple Paragraph blocks so no single block exceeds the Compose
        // window. The image markdown lands in the last chunk as the inline
        // parser's [alt] link fallback — documented behavior there.
        val long = "x".repeat(BoundedText.MAX_ICU_INPUT_CHARS + 1)
        val blocks = parseMarkdownBlocks("$long ![alt](x.png)")
        assertTrue(blocks.size >= 2)
        assertTrue(blocks.all { it is MdBlock.Paragraph })
    }
}
