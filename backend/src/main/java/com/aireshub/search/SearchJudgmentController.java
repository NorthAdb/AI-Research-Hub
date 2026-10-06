package com.aireshub.search;

import com.aireshub.common.ApiException;
import com.aireshub.common.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 检索标准答案，以及用这些答案给当前索引打分。 */
@RestController
public class SearchJudgmentController {

    public record CreateJudgment(@NotBlank String query, @NotBlank String documentId) {
    }

    private final SearchEvaluationService evaluationService;

    public SearchJudgmentController(SearchEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/api/knowledge-bases/{kbId}/judgments")
    public List<SearchEvaluationService.JudgmentView> list(@PathVariable UUID kbId,
                                                           @AuthenticationPrincipal AuthUser user) {
        return evaluationService.list(user.id(), kbId);
    }

    @PostMapping("/api/knowledge-bases/{kbId}/judgments")
    @ResponseStatus(HttpStatus.CREATED)
    public SearchEvaluationService.JudgmentView add(@PathVariable UUID kbId,
                                                    @Valid @RequestBody CreateJudgment request,
                                                    @AuthenticationPrincipal AuthUser user) {
        return evaluationService.add(user.id(), kbId, request.query(), parseId(request.documentId()));
    }

    @DeleteMapping("/api/knowledge-bases/{kbId}/judgments/{judgmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID kbId,
                       @PathVariable UUID judgmentId,
                       @AuthenticationPrincipal AuthUser user) {
        evaluationService.delete(user.id(), kbId, judgmentId);
    }

    @PostMapping("/api/knowledge-bases/{kbId}/search-evaluation")
    public SearchEvaluationService.Evaluation evaluate(@PathVariable UUID kbId,
                                                       @RequestParam(defaultValue = "5") int k,
                                                       @AuthenticationPrincipal AuthUser user) {
        return evaluationService.evaluate(user.id(), kbId, k);
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("documentId 不是合法 UUID");
        }
    }
}
