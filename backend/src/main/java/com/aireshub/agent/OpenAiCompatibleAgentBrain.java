package com.aireshub.agent;

import com.aireshub.agent.AgentBrain.Decision;
import com.aireshub.agent.AgentBrain.StepView;
import com.aireshub.agent.AgentBrain.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 用 OpenAI 兼容的工具调用决定下一步。
 * 激活条件：AGENT_BRAIN=llm，并且 LLM_API_KEY 已配置。
 * 问答用的 LLM_PROVIDER 可以仍是 extractive，两条路径互不替换。
 */
@Component
@ConditionalOnProperty(name = "app.agent.brain", havingValue = "llm")
public class OpenAiCompatibleAgentBrain implements AgentBrain {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    public OpenAiCompatibleAgentBrain(
            ObjectMapper objectMapper,
            @Value("${app.llm.base-url}") String baseUrl,
            @Value("${app.llm.api-key}") String apiKey,
            @Value("${app.llm.model:deepseek-chat}") String model,
            @Value("${app.llm.timeout-seconds:60}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "AGENT_BRAIN=llm 但 LLM_API_KEY 为空。不配置 Key 时请保持 AGENT_BRAIN=rule。");
        }
        this.objectMapper = objectMapper;
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Decision decide(String question, List<StepView> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", """
                你是知识库研究助手。只能依据工具返回的资料回答，不要编造。
                资料不够时调用工具。资料足够时直接写出最终回答，并在句末用资料中的编号标注来源，例如 [1]。
                每次最多调用一个工具。
                """));
        messages.add(Map.of("role", "user", "content", render(question, history)));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post()
                .uri("/chat/completions")
                .body(Map.of(
                        "model", model,
                        "temperature", 0.2,
                        "messages", messages,
                        "tools", toolDefinitions(),
                        "tool_choice", "auto"))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            return new Decision(Optional.empty(), Optional.empty());
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof Map<?, ?> first)) {
            return new Decision(Optional.empty(), Optional.empty());
        }
        Object message = first.get("message");
        if (!(message instanceof Map<?, ?> raw)) {
            return new Decision(Optional.empty(), Optional.empty());
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return parseMessage(normalized, objectMapper);
    }

    /** 从一条 assistant message 里取出工具调用或最终回答。有工具调用时优先执行工具。 */
    public static Decision parseMessage(Map<String, Object> message, ObjectMapper objectMapper) {
        Object toolCalls = message.get("tool_calls");
        if (toolCalls instanceof List<?> calls && !calls.isEmpty() && calls.get(0) instanceof Map<?, ?> call) {
            Object function = call.get("function");
            if (function instanceof Map<?, ?> fn) {
                String name = fn.get("name") == null ? "" : String.valueOf(fn.get("name")).trim();
                if (!name.isEmpty()) {
                    return new Decision(Optional.of(new ToolCall(name, parseArgs(fn.get("arguments"), objectMapper))),
                            Optional.empty());
                }
            }
        }
        Object content = message.get("content");
        String text = content == null ? "" : String.valueOf(content).trim();
        if (text.isEmpty() || "null".equals(text)) {
            return new Decision(Optional.empty(), Optional.empty());
        }
        return new Decision(Optional.empty(), Optional.of(text));
    }

    private static Map<String, Object> parseArgs(Object raw, ObjectMapper objectMapper) {
        try {
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> args = new LinkedHashMap<>();
                map.forEach((key, value) -> args.put(String.valueOf(key), value));
                return args;
            }
            if (raw instanceof String text && !text.isBlank()) {
                return objectMapper.readValue(text, MAP_TYPE);
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.of();
    }

    private static String render(String question, List<StepView> history) {
        StringBuilder body = new StringBuilder();
        body.append("问题：").append(question).append("\n");
        if (history.isEmpty()) {
            body.append("还没有执行过工具。");
            return body.toString();
        }
        body.append("已执行的步骤：\n");
        for (StepView step : history) {
            body.append(step.stepNo()).append(". ").append(step.tool())
                    .append(" ").append(step.args()).append("\n")
                    .append(step.resultSummary() == null ? "" : step.resultSummary())
                    .append("\n");
        }
        return body.toString();
    }

    private static List<Map<String, Object>> toolDefinitions() {
        return List.of(
                function("search_knowledge_base", "在当前知识库里检索资料", Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string", "description", "检索问题")),
                        "required", List.of("query"))),
                function("list_documents", "列出当前知识库的文档", Map.of(
                        "type", "object",
                        "properties", Map.of())),
                function("get_document", "读取一篇文档的前几段原文", Map.of(
                        "type", "object",
                        "properties", Map.of("document_id", Map.of("type", "string", "description", "文档 UUID")),
                        "required", List.of("document_id"))),
                function("summarize_document", "摘出一篇文档里的关键句子", Map.of(
                        "type", "object",
                        "properties", Map.of("document_id", Map.of("type", "string", "description", "文档 UUID")),
                        "required", List.of("document_id"))));
    }

    private static Map<String, Object> function(String name, String description, Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of("name", name, "description", description, "parameters", parameters));
    }
}
