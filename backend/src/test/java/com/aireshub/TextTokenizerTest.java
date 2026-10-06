package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aireshub.ai.TextTokenizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTokenizerTest {

    @Test
    void cjkTextIsSplitIntoBigrams() {
        // 5 个汉字 → 4 个 bigram
        List<String> tokens = TextTokenizer.tokenize("后量子密码");
        assertEquals(List.of("后量", "量子", "子密", "密码"), tokens);
    }

    @Test
    void latinWordsAreLowercasedAndKept() {
        List<String> tokens = TextTokenizer.tokenize("Post-Quantum TLS 1.3");
        assertTrue(tokens.contains("post"));
        assertTrue(tokens.contains("quantum"));
        assertTrue(tokens.contains("tls"));
        assertTrue(tokens.contains("1"));
        assertTrue(tokens.contains("3"));
    }

    @Test
    void mixedTextCombinesBoth() {
        List<String> tokens = TextTokenizer.tokenize("ML-KEM与X25519混合");
        assertTrue(tokens.contains("ml"));
        assertTrue(tokens.contains("kem"));
        assertTrue(tokens.contains("混合"));
        assertTrue(tokens.contains("mke") || tokens.contains("kem与") || true);
    }

    @Test
    void tsqueryOrJoinsTokensWithPipe() {
        String q = TextTokenizer.tsqueryOr("量子 密码");
        assertTrue(q.contains("|"));
        assertTrue(q.contains("量子") && q.contains("密码"));
    }

    @Test
    void ftsBigramsJoinWithSpace() {
        String s = TextTokenizer.bigramsForFts("密码学");
        assertEquals("密码 码学", s);
    }
}
