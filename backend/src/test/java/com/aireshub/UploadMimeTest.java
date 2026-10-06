package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aireshub.document.DocumentService;
import org.junit.jupiter.api.Test;

class UploadMimeTest {

    @Test
    void acceptsExtensionWhenBrowserSendsGenericType() {
        assertEquals("text/markdown", DocumentService.resolveMime("application/octet-stream", "notes.md"));
        assertEquals("application/pdf", DocumentService.resolveMime(null, "paper.PDF"));
        assertEquals("text/plain", DocumentService.resolveMime("", "readme.txt"));
    }

    @Test
    void keepsExplicitAllowedType() {
        assertEquals("text/markdown", DocumentService.resolveMime("text/markdown; charset=utf-8", "notes.md"));
    }

    @Test
    void rejectsUnknownType() {
        assertNull(DocumentService.resolveMime("image/png", "photo.png"));
    }
}
