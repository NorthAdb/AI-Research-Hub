package com.aireshub.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 规则式大脑(无 LLM Key 的默认实现,确定性可复现):
 * 第一步必做 search_knowledge_base;问题含总结意图且未总结过 → summarize_document;否则给出最终回答。
 */
@Component
@ConditionalOnProperty(name = "app.agent.brain", havingValue = "rule", matchIfMissing = true)
public class RuleBasedAgentBrain implements AgentBrain {

    @Override
    public Decision decide(String question, List<StepView> history) {
        if (history.isEmpty()) {
            return new Decision(Optional.of(new ToolCall("search_knowledge_base",
                    Map.of("query", question))), Optional.empty());
        }
        boolean wantsSummary = question.matches(".*(总结|概括|摘要|综述).*");
        boolean summarized = history.stream().anyMatch(s -> "summarize_document".equals(s.tool()));
        if (wantsSummary && !summarized) {
            // 从最近一次 search 结果中取第一个文档
            for (int i = history.size() - 1; i >= 0; i--) {
                StepView step = history.get(i);
                if ("search_knowledge_base".equals(step.tool()) && step.data() != null) {
                    Object docId = step.data().get("firstDocumentId");
                    if (docId != null) {
                        return new Decision(Optional.of(new ToolCall("summarize_document",
                                Map.of("document_id", docId))), Optional.empty());
                    }
                }
            }
        }
        return new Decision(Optional.empty(), Optional.empty());
    }
}
