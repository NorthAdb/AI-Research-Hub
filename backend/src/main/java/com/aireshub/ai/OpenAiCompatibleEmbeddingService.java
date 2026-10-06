package com.aireshub.ai;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 Embeddings API。
 * 激活条件:app.embedding.provider=openai,且 EMBEDDING_API_KEY 已配置。
 * 返回向量的长度必须等于数据库列 vector(N),否则拒绝写入,避免把索引写坏。
 */
@Component
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "openai")
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final RestClient client;
    private final String model;
    private final int dimensions;

    public OpenAiCompatibleEmbeddingService(
            @Value("${app.embedding.base-url}") String baseUrl,
            @Value("${app.embedding.api-key}") String apiKey,
            @Value("${app.embedding.model}") String model,
            @Value("${app.embedding.dims:768}") int dimensions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "EMBEDDING_PROVIDER=openai 但 EMBEDDING_API_KEY 为空。不配置 Key 时请保持 EMBEDDING_PROVIDER=local。");
        }
        this.model = model;
        this.dimensions = dimensions;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public float[] embed(String text) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post()
                .uri("/embeddings")
                .body(Map.of(
                        "model", model,
                        "input", text == null ? "" : text))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("嵌入接口没有返回内容");
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof Map<?, ?> first)) {
            throw new IllegalStateException("嵌入接口返回格式无法识别");
        }
        Object raw = first.get("embedding");
        if (!(raw instanceof List<?> values)) {
            throw new IllegalStateException("嵌入接口返回格式无法识别");
        }
        return toVector(values, dimensions);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /** 校验长度后转成 float[]。长度不对时直接失败,不把错误维度写进 pgvector。 */
    public static float[] toVector(List<?> raw, int dimensions) {
        if (raw.size() != dimensions) {
            throw new IllegalStateException("嵌入维度是 " + raw.size()
                    + ",数据库列是 vector(" + dimensions + ")。"
                    + "请换一个同维度模型,或先修改 document_chunks.embedding 并重建全部索引。");
        }
        float[] out = new float[dimensions];
        for (int i = 0; i < raw.size(); i++) {
            Object value = raw.get(i);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("嵌入向量含有非数字元素");
            }
            out[i] = number.floatValue();
        }
        return out;
    }
}
