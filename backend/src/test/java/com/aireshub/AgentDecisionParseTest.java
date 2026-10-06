package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aireshub.agent.OpenAiCompatibleAgentBrain;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDecisionParseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void prefersToolCallOverAccompanyingText() {
        var decision = OpenAiCompatibleAgentBrain.parseMessage(Map.of(
                "content", "我先查一下",
                "tool_calls", List.of(Map.of(
                        "function", Map.of(
                                "name", "search_knowledge_base",
                                "arguments", "{\"query\":\"ML-DSA 证书\"}")))),
                objectMapper);
        assertTrue(decision.toolCall().isPresent());
        assertEquals("search_knowledge_base", decision.toolCall().get().tool());
        assertEquals("ML-DSA 证书", decision.toolCall().get().args().get("query"));
        assertTrue(decision.finalAnswer().isEmpty());
    }

    @Test
    void treatsPlainContentAsFinalAnswer() {
        var decision = OpenAiCompatibleAgentBrain.parseMessage(
                Map.of("content", "证书签名约 3309 字节。[1]"), objectMapper);
        assertTrue(decision.toolCall().isEmpty());
        assertEquals("证书签名约 3309 字节。[1]", decision.finalAnswer().orElseThrow());
    }

    @Test
    void emptyArgumentsDoNotFailTheTurn() {
        var decision = OpenAiCompatibleAgentBrain.parseMessage(Map.of(
                "tool_calls", List.of(Map.of(
                        "function", Map.of("name", "list_documents", "arguments", "not-json")))),
                objectMapper);
        assertTrue(decision.toolCall().isPresent());
        assertTrue(decision.toolCall().get().args().isEmpty());
    }
}
