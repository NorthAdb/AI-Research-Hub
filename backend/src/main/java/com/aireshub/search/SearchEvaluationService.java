package com.aireshub.search;

import com.aireshub.common.ApiException;
import com.aireshub.document.DocumentRepository;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标准答案存在 search_judgments。评测时用当前检索管道重跑这些问题，
 * 不拿历史 search_logs 充数，因为索引变了以后旧日志不能代表现在的效果。
 */
@Service
public class SearchEvaluationService {

    public record JudgmentView(UUID id, String query, UUID documentId, String documentTitle, Instant createdAt) {
    }

    public record Detail(String query, int relevant, int found, Integer firstRank,
                         List<UUID> expectedDocumentIds, List<UUID> retrievedDocumentIds) {
    }

    public record Evaluation(int k, int queries, int hits, double recallAtK, double mrr, List<Detail> details) {
    }

    private final SearchJudgmentRepository judgmentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final SearchService searchService;

    public SearchEvaluationService(SearchJudgmentRepository judgmentRepository,
                                   KnowledgeBaseRepository kbRepository,
                                   DocumentRepository documentRepository,
                                   SearchService searchService) {
        this.judgmentRepository = judgmentRepository;
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.searchService = searchService;
    }

    @Transactional
    public JudgmentView add(UUID userId, UUID kbId, String query, UUID documentId) {
        owned(kbId, userId);
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("query 不能为空");
        }
        var document = documentRepository.findByKnowledgeBaseIdAndId(kbId, documentId)
                .orElseThrow(() -> ApiException.badRequest("文档不在这个知识库里"));
        if (judgmentRepository.existsByKnowledgeBaseIdAndQueryAndDocumentId(kbId, normalized, documentId)) {
            throw ApiException.conflict("这条标准答案已经存在");
        }
        SearchJudgment judgment = new SearchJudgment();
        judgment.setKnowledgeBaseId(kbId);
        judgment.setQuery(normalized);
        judgment.setDocumentId(documentId);
        judgment.setCreatedAt(Instant.now());
        judgmentRepository.save(judgment);
        return new JudgmentView(judgment.getId(), normalized, documentId, document.getTitle(), judgment.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<JudgmentView> list(UUID userId, UUID kbId) {
        owned(kbId, userId);
        return judgmentRepository.findByKnowledgeBaseIdOrderByCreatedAtAsc(kbId).stream()
                .map(item -> new JudgmentView(item.getId(), item.getQuery(), item.getDocumentId(),
                        titleOf(item.getDocumentId()), item.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void delete(UUID userId, UUID kbId, UUID judgmentId) {
        owned(kbId, userId);
        SearchJudgment judgment = judgmentRepository.findById(judgmentId)
                .filter(item -> item.getKnowledgeBaseId().equals(kbId))
                .orElseThrow(() -> ApiException.notFound("标准答案不存在"));
        judgmentRepository.delete(judgment);
    }

    /** 按当前索引重跑全部标注，计算 Recall@K 和 MRR。 */
    public Evaluation evaluate(UUID userId, UUID kbId, int k) {
        owned(kbId, userId);
        int topK = Math.min(Math.max(k, 1), 20);
        List<SearchJudgment> judgments = judgmentRepository.findByKnowledgeBaseIdOrderByCreatedAtAsc(kbId);
        Map<String, LinkedHashSet<UUID>> expected = new LinkedHashMap<>();
        for (SearchJudgment judgment : judgments) {
            expected.computeIfAbsent(judgment.getQuery(), key -> new LinkedHashSet<>()).add(judgment.getDocumentId());
        }
        List<SearchMetrics.QueryCase> cases = new ArrayList<>();
        Map<String, List<UUID>> retrievedByQuery = new LinkedHashMap<>();
        for (var entry : expected.entrySet()) {
            var outcome = searchService.search(userId, kbId, entry.getKey(), topK);
            List<UUID> retrieved = new ArrayList<>();
            for (ScoredResult result : outcome.results()) {
                retrieved.add(result.documentId());
            }
            retrievedByQuery.put(entry.getKey(), retrieved);
            cases.add(new SearchMetrics.QueryCase(entry.getKey(), List.copyOf(entry.getValue()), retrieved));
        }
        SearchMetrics.Summary summary = SearchMetrics.score(cases);
        List<Detail> details = new ArrayList<>();
        for (SearchMetrics.QueryScore score : summary.details()) {
            details.add(new Detail(score.query(), score.relevant(), score.found(), score.firstRank(),
                    List.copyOf(expected.get(score.query())), retrievedByQuery.get(score.query())));
        }
        return new Evaluation(topK, summary.queries(), summary.hits(), summary.recallAtK(), summary.mrr(), details);
    }

    private void owned(UUID kbId, UUID userId) {
        var kb = kbRepository.findById(kbId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
        if (!kb.getOwnerId().equals(userId)) {
            throw ApiException.forbidden("无权访问该知识库");
        }
    }

    private String titleOf(UUID documentId) {
        return documentRepository.findById(documentId).map(doc -> doc.getTitle()).orElse("(已删除)");
    }

    static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.strip().replaceAll("\\s+", " ");
    }
}
