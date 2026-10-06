package com.aireshub.document;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/**
 * 文本抽取(路线图 DocumentParser 的第一版实现):
 * PDF 走 PDFBox 按页抽取;Markdown/纯文本按 UTF-8 单页读入。
 * Phase 5+ 替换/增强解析器时只动这里。
 */
@Service
public class TextExtractionService {

    public record PageText(int page, String text) {
    }

    public List<PageText> extract(String mimeType, byte[] bytes) {
        if (mimeType != null && mimeType.contains("pdf")) {
            return extractPdf(bytes);
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return List.of(new PageText(1, text));
    }

    private List<PageText> extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<PageText> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text != null && !text.isBlank()) {
                    pages.add(new PageText(page, text));
                }
            }
            if (pages.isEmpty()) {
                throw new IllegalStateException("PDF 中没有可抽取的文本(可能是扫描件)");
            }
            return pages;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
    }
}
