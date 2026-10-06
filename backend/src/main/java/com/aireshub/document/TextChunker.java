package com.aireshub.document;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 第一版切块(路线图 §12):固定字符窗口 + overlap + metadata(page)。
 * 参数外置:app.rag.chunk-size / app.rag.chunk-overlap。
 */
@Component
public class TextChunker {

    private final int chunkSize;
    private final int overlap;

    public TextChunker(@Value("${app.rag.chunk-size:800}") int chunkSize,
                       @Value("${app.rag.chunk-overlap:100}") int overlap) {
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("chunk-overlap 必须小于 chunk-size");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public record ChunkRecord(int index, String content, int page) {
    }

    public List<ChunkRecord> chunk(List<TextExtractionService.PageText> pages) {
        // 1) 拼接全文,同时记录每个字符所属页码(分段记录页边界)
        StringBuilder full = new StringBuilder();
        List<int[]> pageBreaks = new ArrayList<>(); // {startOffset, page}
        for (TextExtractionService.PageText page : pages) {
            pageBreaks.add(new int[]{full.length(), page.page()});
            String text = page.text().replaceAll("[ \\t]+", " ");
            full.append(text).append('\n');
        }
        String text = full.toString();
        int[] breaks = new int[pageBreaks.size() + 1];
        for (int i = 0; i < pageBreaks.size(); i++) {
            breaks[i] = pageBreaks.get(i)[0];
        }
        breaks[pageBreaks.size()] = text.length();

        // 2) 滑动窗口切块,窗口结束点尽量落在句子/段落边界
        List<ChunkRecord> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        int index = 0;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                end = snapToBoundary(text, start, end);
                if (end - start < chunkSize / 4) { // 边界吸附失败则硬切
                    end = Math.min(start + chunkSize, text.length());
                }
            }
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new ChunkRecord(index++, content, pageOf(breaks, start)));
            }
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    /** 在 [end-120, end] 内找最近的句子结束符,找到则切在其后。 */
    private int snapToBoundary(String text, int start, int end) {
        int from = Math.max(start + (end - start) * 3 / 4, start);
        int cut = -1;
        for (int i = end - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '!' || c == '?' || c == '\n' || c == ';' || c == '.') {
                cut = i + 1;
                break;
            }
        }
        return cut > 0 ? cut : end;
    }

    private int pageOf(int[] breaks, int offset) {
        for (int i = 1; i < breaks.length; i++) {
            if (offset < breaks[i]) {
                return i; // 第 i 段的页码就是 i(1-based)
            }
        }
        return breaks.length - 1;
    }
}
