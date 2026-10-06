package com.aireshub.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 Chat API 实现(DeepSeek / OpenAI / 硅基流动等)。
 * 激活条件:app.llm.provider=openai 且 app.llm.api-key 已配置。
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")
public class OpenAiCompatibleLlmService implements LlmService {

    private final RestClient client;
    private final String model;

    public OpenAiCompatibleLlmService(
            @Value("${app.llm.base-url}") String baseUrl,
            @Value("${app.llm.api-key}") String apiKey,
            @Value("${app.llm.model:deepseek-chat}") String model,
            @Value("${app.llm.timeout-seconds:60}") long timeoutSeconds) {
        this.model = model;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public LlmResult answer(String question, List<CitedContext> contexts) {
        StringBuilder contextBlock = new StringBuilder();
        for (CitedContext ctx : contexts) {
            contextBlock.append("[").append(ctx.n()).append("] ").append(ctx.text()).append("\n\n");
        }
        String system = """
                你是知识库问答助手。只依据提供的资料回答问题;
                每个论断都要在句尾标注其来源资料的编号,形如 [1] 或 [2][3];
                如果资料不足以回答,直接说明资料不足,不要编造。
                """;
        String user = "问题:" + question + "\n\n资料:\n" + contextBlock;

        var body = java.util.Map.of(
                "model", model,
                "messages", List.of(
                        java.util.Map.of("role", "system", "content", system),
                        java.util.Map.of("role", "user", "content", user)),
                "temperature", 0.2);

        @SuppressWarnings("unchecked")
        var response = client.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(java.util.Map.class);

        if (response == null) {
            return new LlmResult("LLM 未返回内容。", "openai:" + model);
        }
        @SuppressWarnings("unchecked")
        var choices = (List<Object>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            return new LlmResult("LLM 未返回内容。", "openai:" + model);
        }
        @SuppressWarnings("unchecked")
        var first = (java.util.Map<String, Object>) choices.get(0);
        @SuppressWarnings("unchecked")
        var message = (java.util.Map<String, Object>) first.get("message");
        String answer = message == null ? "" : String.valueOf(message.getOrDefault("content", ""));
        return new LlmResult(answer.isBlank() ? "LLM 未返回内容。" : answer.trim(), "openai:" + model);
    }
}
