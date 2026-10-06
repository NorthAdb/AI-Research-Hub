package com.aireshub.search;

import com.aireshub.ai.EmbeddingService;
import com.aireshub.ai.RerankerService;
import com.aireshub.document.ChunkVectorRepository;
import com.aireshub.knowledge.KnowledgeBase;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hybrid Search 管道(路线图 Phase 6 / §13):
 * Query → Dense(pgvector) + Sparse(PG FTS) → RRF 融合 → Reranker → TopK,
 * 全程留痕 search_logs。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ChunkVectorRepository vectorRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final SearchLogRepository searchLogRepository;
    private final EmbeddingService embeddingService;
    private final RerankerService rerankerService;
    private final SparseSearch sparseSearch;
    private final ObjectMapper objectMapper;
    private final int candidateLimit;
    private final int rrfK;

    public SearchService(ChunkVectorRepository vectorRepository,
                         KnowledgeBaseRepository kbRepository,
                         SearchLogRepository searchLogRepository,
                         EmbeddingService embeddingService,
                         RerankerService rerankerService,
                         SparseSearch sparseSearch,
                         ObjectMapper objectMapper,
                         @Value("${app.search.candidate-limit:30}") int candidateLimit,
                         @Value("${app.search.rrf-k:60}") int rrfK) {
        this.vectorRepository = vectorRepository;
        this.kbRepository = kbRepository;
        this.searchLogRepository = searchLogRepository;
        this.embeddingService = embeddingService;
        this.rerankerService = rerankerService;
        this.sparseSearch = sparseSearch;
        this.objectMapper = objectMapper;
        this.candidateLimit = candidateLimit;
        this.rrfK = rrfK;
    }

    /** trace 是各阶段 top 结果的 chunkId(供前端与评估观察)。 */
    public record Trace(List<Map<String, Object>> dense, List<Map<String, Object>> sparse,
                        List<Map<String, Object>> fused, List<Map<String, Object>> reranked,
                        String sparseBackend) {
    }

    public record SearchOutcome(UUID searchId, List<ScoredResult> results, Trace trace, long latencyMs) {
    }

    public SearchOutcome search(UUID userId, UUID kbId, String query, int topK) {
        long start = System.currentTimeMillis();
        KnowledgeBase kb = kbRepository.findById(kbId).orElseThrow();

        // 1) Dense:query 向量 → pgvector 余弦检索
        float[] queryVector = embeddingService.embed(query);
        List<ScoredResult> dense =
                vectorRepository.denseSearch(kb.getId(), toVectorString(queryVector), candidateLimit);

        // 2) Sparse:默认 PostgreSQL 全文；SEARCH_SPARSE=elasticsearch 时走 Elasticsearch
        List<ScoredResult> sparse = sparseSearch.search(kb.getId(), query, candidateLimit);

        // 3) RRF 融合:score = Σ 1/(k + rank)
        List<ScoredResult> fused = rrfFuse(List.of(dense, sparse));

        // 4) Rerank → topK
        List<ScoredResult> reranked = rerankerService.rerank(query, fused, topK);
        long latency = System.currentTimeMillis() - start;

        Trace trace = new Trace(
                summarize(dense), summarize(sparse), summarize(fused), summarize(reranked),
                sparseSearch.name());
        persistLog(userId, kb.getId(), query, trace, latency);
        return new SearchOutcome(null, reranked, trace, latency);
    }

    /** Reciprocal Rank Fusion,保持 chunkId 去重与稳定顺序。 */
    private List<ScoredResult> rrfFuse(List<List<ScoredResult>> rankedLists) {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        Map<UUID, ScoredResult> byId = new LinkedHashMap<>();
        for (List<ScoredResult> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                ScoredResult r = list.get(rank);
                scores.merge(r.chunkId(), 1.0 / (rrfK + rank + 1), Double::sum);
                byId.putIfAbsent(r.chunkId(), r);
            }
        }
        List<ScoredResult> fused = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : scores.entrySet()) {
            ScoredResult base = byId.get(e.getKey());
            fused.add(base.copyWithScore(e.getValue()));
        }
        fused.sort((a, b) -> Double.compare(b.score(), a.score()));
        return fused;
    }

    private List<Map<String, Object>> summarize(List<ScoredResult> results) {
        return results.stream()
                .limit(10)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunkId", r.chunkId().toString());
                    m.put("documentTitle", r.documentTitle());
                    m.put("score", Math.round(r.score() * 10000) / 10000.0);
                    return m;
                })
                .toList();
    }

    private void persistLog(UUID userId, UUID kbId, String query, Trace trace, long latencyMs) {
        try {
            SearchLog logEntry = new SearchLog();
            logEntry.setUserId(userId);
            logEntry.setKnowledgeBaseId(kbId);
            logEntry.setQuery(query);
            logEntry.setDenseResults(objectMapper.writeValueAsString(trace.dense()));
            logEntry.setSparseResults(objectMapper.writeValueAsString(trace.sparse()));
            logEntry.setFusedResults(objectMapper.writeValueAsString(trace.fused()));
            logEntry.setRerankedResults(objectMapper.writeValueAsString(trace.reranked()));
            logEntry.setLatencyMs((int) latencyMs);
            searchLogRepository.save(logEntry);
        } catch (Exception ex) {
            log.warn("search_logs 写入失败(不影响检索)", ex);
        }
    }

    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
