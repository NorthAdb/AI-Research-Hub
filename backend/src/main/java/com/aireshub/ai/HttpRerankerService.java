package com.aireshub.ai;

import com.aireshub.search.ScoredResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cohere / 硅基流动兼容的 /rerank 接口。
 * 激活条件：RERANKER_PROVIDER=api，并且 RERANKER_API_KEY 已配置。
 * 返回顺序就是新的排序，分数换成接口给的相关性分数。
 */
@Component
@ConditionalOnProperty(name = "app.reranker.provider", havingValue = "api")
public class HttpRerankerService implements RerankerService {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    public HttpRerankerService(
            ObjectMapper objectMapper,
            @Value("${app.reranker.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${app.reranker.api-key}") String apiKey,
            @Value("${app.reranker.model:BAAI/bge-reranker-v2-m3}") String model,
            @Value("${app.reranker.timeout-seconds:30}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "RERANKER_PROVIDER=api 但 RERANKER_API_KEY 为空。不配置 Key 时请保持 RERANKER_PROVIDER=lexical。");
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
    public List<ScoredResult> rerank(String query, List<ScoredResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> documents = candidates.stream().map(ScoredResult::content).toList();
        String body = client.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", model,
                        "query", query == null ? "" : query,
                        "documents", documents,
                        "top_n", Math.min(topK, candidates.size())))
                .retrieve()
                .body(String.class);
        try {
            return applyScores(candidates, objectMapper.readTree(body).path("results"), topK);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析重排接口的返回", ex);
        }
    }

    /** 按接口返回的 index 重排。越靠前越相关；非法下标跳过。 */
    public static List<ScoredResult> applyScores(List<ScoredResult> candidates, JsonNode results, int topK) {
        List<ScoredResult> reranked = new ArrayList<>();
        if (results == null || !results.isArray()) {
            return reranked;
        }
        for (JsonNode item : results) {
            if (reranked.size() >= topK) {
                break;
            }
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            double score = item.has("relevance_score")
                    ? item.path("relevance_score").asDouble()
                    : item.path("score").asDouble();
            reranked.add(candidates.get(index).copyWithScore(score));
        }
        return reranked;
    }
}
