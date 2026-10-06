package com.aireshub.agent;

import com.aireshub.common.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Agentic RAG 入口(路线图 Phase 11)。 */
@RestController
public class AgentController {

    public record AgentRequest(@NotBlank @Size(max = 2000) String question) {
    }

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/api/knowledge-bases/{kbId}/agent")
    public AgentService.AgentResponse run(@PathVariable UUID kbId,
                                          @Valid @RequestBody AgentRequest request,
                                          @AuthenticationPrincipal AuthUser user) {
        return agentService.run(user.id(), kbId, request.question());
    }

    @GetMapping("/api/agent/runs/{runId}")
    public AgentRunView getRun(@PathVariable UUID runId,
                               @AuthenticationPrincipal AuthUser user) {
        var view = agentService.getRun(user.id(), runId);
        return new AgentRunView(view.run().getId(), view.run().getInput(),
                view.run().getFinalAnswer(), view.run().getLatencyMs(),
                view.steps().stream()
                        .map(s -> new AgentService.AgentStepView(s.getStepNo(), s.getTool(),
                                null, s.getResultSummary(), s.getLatencyMs()))
                        .toList());
    }

    public record AgentRunView(UUID runId, String input, String finalAnswer,
                               Integer latencyMs, List<AgentService.AgentStepView> steps) {
    }
}
