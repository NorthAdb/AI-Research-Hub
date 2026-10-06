package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aireshub.ai.HttpRerankerService;
import com.aireshub.search.ScoredResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpRerankerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reordersByApiIndexAndScore() throws Exception {
        ScoredResult first = result("第一段");
        ScoredResult second = result("第二段");
        var results = objectMapper.readTree("""
                [{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}]
                """);
        List<ScoredResult> reranked = HttpRerankerService.applyScores(List.of(first, second), results, 5);
        assertEquals(second.chunkId(), reranked.get(0).chunkId());
        assertEquals(0.9, reranked.get(0).score());
        assertEquals(first.chunkId(), reranked.get(1).chunkId());
    }

    @Test
    void skipsBadIndexesAndHonorsTopK() throws Exception {
        ScoredResult only = result("仅有的一段");
        var results = objectMapper.readTree("""
                [{"index":3,"score":0.4},{"index":0,"score":0.8},{"index":0,"score":0.1}]
                """);
        List<ScoredResult> reranked = HttpRerankerService.applyScores(List.of(only), results, 1);
        assertEquals(1, reranked.size());
        assertEquals(0.8, reranked.get(0).score());
    }

    private static ScoredResult result(String content) {
        return new ScoredResult(UUID.randomUUID(), UUID.randomUUID(), "标题", content, 0.1);
    }
}
