package com.aireshub.search;

import com.aireshub.common.ApiException;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读取已经落库的检索日志,供人工看每次检索走了哪些阶段。
 * 这还不是带标准答案的评测集,只是把 search_logs 变成可查询的数据。
 */
@Service
public class SearchLogService {

    public record LogView(UUID id, String query, Integer latencyMs, Instant createdAt, int rerankedCount) {
    }

    public record Report(int queries, int emptyResults, Double avgLatencyMs) {
    }

    private final SearchLogRepository searchLogRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ObjectMapper objectMapper;

    public SearchLogService(SearchLogRepository searchLogRepository,
                            KnowledgeBaseRepository kbRepository,
                            ObjectMapper objectMapper) {
        this.searchLogRepository = searchLogRepository;
        this.kbRepository = kbRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<LogView> recent(UUID userId, UUID kbId, int limit) {
        owned(kbId, userId);
        int size = Math.min(Math.max(limit, 1), 50);
        return searchLogRepository
                .findByUserIdAndKnowledgeBaseIdOrderByCreatedAtDesc(userId, kbId, PageRequest.of(0, size))
                .stream()
                .map(log -> new LogView(log.getId(), log.getQuery(), log.getLatencyMs(),
                        log.getCreatedAt(), countArray(log.getRerankedResults())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Report report(UUID userId, UUID kbId) {
        owned(kbId, userId);
        List<SearchLog> logs = searchLogRepository
                .findByUserIdAndKnowledgeBaseIdOrderByCreatedAtDesc(userId, kbId, PageRequest.of(0, 200));
        int empty = 0;
        long latencySum = 0;
        int latencyCount = 0;
        for (SearchLog log : logs) {
            if (countArray(log.getRerankedResults()) == 0) {
                empty++;
            }
            if (log.getLatencyMs() != null) {
                latencySum += log.getLatencyMs();
                latencyCount++;
            }
        }
        Double avg = latencyCount == 0 ? null : Math.round(latencySum * 10.0 / latencyCount) / 10.0;
        return new Report(logs.size(), empty, avg);
    }

    private void owned(UUID kbId, UUID userId) {
        var kb = kbRepository.findById(kbId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
        if (!kb.getOwnerId().equals(userId)) {
            throw ApiException.forbidden("无权访问该知识库");
        }
    }

    private int countArray(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isArray() ? node.size() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }
}
