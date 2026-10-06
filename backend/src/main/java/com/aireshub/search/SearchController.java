package com.aireshub.search;

import com.aireshub.common.ApiException;
import com.aireshub.common.AuthUser;
import com.aireshub.knowledge.KnowledgeBase;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Hybrid Search 调试/评估入口(路线图 Phase 6:POST /api/search)。 */
@RestController
public class SearchController {

    public record SearchRequest(@NotBlank String knowledgeBaseId, @NotBlank String query) {
    }

    public record SearchResponse(List<ScoredResult> results, SearchService.Trace trace, long latencyMs) {
    }

    private final SearchService searchService;
    private final KnowledgeBaseRepository kbRepository;
    private final SearchLogService searchLogService;

    public SearchController(SearchService searchService,
                            KnowledgeBaseRepository kbRepository,
                            SearchLogService searchLogService) {
        this.searchService = searchService;
        this.kbRepository = kbRepository;
        this.searchLogService = searchLogService;
    }

    @PostMapping("/api/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request,
                                 @AuthenticationPrincipal AuthUser user) {
        UUID kbId;
        try {
            kbId = UUID.fromString(request.knowledgeBaseId());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("knowledgeBaseId 不是合法 UUID");
        }
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
        if (!kb.getOwnerId().equals(user.id())) {
            throw ApiException.forbidden("无权访问该知识库");
        }
        var outcome = searchService.search(user.id(), kb.getId(), request.query(), 5);
        return new SearchResponse(outcome.results(), outcome.trace(), outcome.latencyMs());
    }

    /** 最近检索记录。用于观察 dense / sparse / 融合 / 重排是否真的产生了结果。 */
    @GetMapping("/api/knowledge-bases/{kbId}/search-logs")
    public java.util.List<SearchLogService.LogView> logs(@PathVariable UUID kbId,
                                                         @RequestParam(defaultValue = "20") int limit,
                                                         @AuthenticationPrincipal AuthUser user) {
        return searchLogService.recent(user.id(), kbId, limit);
    }

    /** 最近 200 次检索的空结果次数和平均耗时。还不是带标准答案的评测。 */
    @GetMapping("/api/knowledge-bases/{kbId}/search-report")
    public SearchLogService.Report report(@PathVariable UUID kbId,
                                          @AuthenticationPrincipal AuthUser user) {
        return searchLogService.report(user.id(), kbId);
    }
}
