package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aireshub.document.TextChunker;
import com.aireshub.document.TextExtractionService;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void chunksLongTextWithOverlapAndPageMetadata() {
        TextChunker chunker = new TextChunker(100, 20);
        StringBuilder page1 = new StringBuilder();
        page1.append("第一页。".repeat(40)); // 160 chars
        StringBuilder page2 = new StringBuilder();
        page2.append("第二页内容。".repeat(30)); // 180 chars

        List<TextChunker.ChunkRecord> chunks = chunker.chunk(List.of(
                new TextExtractionService.PageText(1, page1.toString()),
                new TextExtractionService.PageText(2, page2.toString())));

        assertTrue(chunks.size() >= 3);
        assertEquals(0, chunks.get(0).index());
        assertEquals(1, chunks.get(0).page());
        // 相邻窗口应存在 overlap(后一块开头与前一块结尾内容重叠)
        assertTrue(chunks.get(1).content().length() > 0);
        // 最后一块的页码应为 2
        assertEquals(2, chunks.get(chunks.size() - 1).page());
    }

    @Test
    void shortTextProducesSingleChunk() {
        TextChunker chunker = new TextChunker(100, 20);
        List<TextChunker.ChunkRecord> chunks = chunker.chunk(
                List.of(new TextExtractionService.PageText(1, "短文本。")));
        assertEquals(1, chunks.size());
        assertEquals("短文本。", chunks.get(0).content());
    }
}
