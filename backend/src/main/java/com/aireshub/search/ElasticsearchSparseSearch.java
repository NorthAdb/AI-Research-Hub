package com.aireshub.search;

import com.aireshub.ai.TextTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Elasticsearch 稀疏检索。中文不依赖 IK 插件，沿用和 PostgreSQL 相同的 bigram，
 * 用 whitespace 分析器按词匹配。这样两条稀疏路径可以对照，而不是各分各的词。
 * 查询必须带知识库过滤，避免搜到别的库。
 */
@Component
@ConditionalOnProperty(name = "app.search.sparse", havingValue = "elasticsearch")
public class ElasticsearchSparseSearch implements SparseSearch {

    static final String INDEX = "arh-chunks";

    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public ElasticsearchSparseSearch(
            ObjectMapper objectMapper,
            @Value("${app.search.elasticsearch-url:http://localhost:9200}") String baseUrl) {
        this(objectMapper, RestClient.builder().baseUrl(baseUrl).build(), true);
    }

    /** ensureIndex=false 只给测试用，避免连真集群。 */
    public ElasticsearchSparseSearch(ObjectMapper objectMapper, RestClient client, boolean ensureIndex) {
        this.objectMapper = objectMapper;
        this.client = client;
        if (ensureIndex) {
            ensureIndex();
        }
    }

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public List<ScoredResult> search(UUID knowledgeBaseId, String query, int limit) {
        String tokens = TextTokenizer.bigramsForFts(query);
        if (tokens.isBlank()) {
            return List.of();
        }
        String body = client.post()
                .uri("/" + INDEX + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(searchBody(knowledgeBaseId, tokens, limit))
                .retrieve()
                .body(String.class);
        return parseHits(body, objectMapper);
    }

    @Override
    public void indexChunk(UUID knowledgeBaseId, UUID documentId, String documentTitle,
                           UUID chunkId, String content) {
        client.put()
                .uri("/" + INDEX + "/_doc/" + chunkId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "knowledgeBaseId", knowledgeBaseId.toString(),
                        "documentId", documentId.toString(),
                        "documentTitle", documentTitle == null ? "" : documentTitle,
                        "content", content == null ? "" : content,
                        "tokens", TextTokenizer.bigramsForFts(content)))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void deleteDocument(UUID documentId) {
        client.post()
                .uri("/" + INDEX + "/_delete_by_query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", Map.of("term", Map.of("documentId", documentId.toString()))))
                .retrieve()
                .toBodilessEntity();
    }

    private void ensureIndex() {
        try {
            client.get().uri("/").retrieve().toBodilessEntity();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "SEARCH_SPARSE=elasticsearch，但连不上 Elasticsearch。请先启动集群，或改回 SEARCH_SPARSE=postgres。",
                    ex);
        }
        int status;
        try {
            status = client.get().uri("/" + INDEX).retrieve().toBodilessEntity().getStatusCode().value();
        } catch (RestClientResponseException ex) {
            status = ex.getStatusCode().value();
        }
        if (status == 404) {
            client.put()
                    .uri("/" + INDEX)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mappings", Map.of("properties", Map.of(
                            "knowledgeBaseId", Map.of("type", "keyword"),
                            "documentId", Map.of("type", "keyword"),
                            "documentTitle", Map.of("type", "keyword"),
                            "content", Map.of("type", "text", "index", false),
                            "tokens", Map.of("type", "text", "analyzer", "whitespace")))))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    static Map<String, Object> searchBody(UUID knowledgeBaseId, String tokens, int limit) {
        return Map.of(
                "size", limit,
                "_source", List.of("documentId", "documentTitle", "content"),
                "query", Map.of("bool", Map.of(
                        "filter", List.of(Map.of("term", Map.of("knowledgeBaseId", knowledgeBaseId.toString()))),
                        "must", List.of(Map.of("match", Map.of("tokens", Map.of("query", tokens, "operator", "or")))))));
    }

    public static List<ScoredResult> parseHits(String json, ObjectMapper objectMapper) {
        List<ScoredResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return results;
        }
        try {
            JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
            if (!hits.isArray()) {
                return results;
            }
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                UUID chunkId = UUID.fromString(hit.path("_id").asText());
                UUID documentId = UUID.fromString(source.path("documentId").asText());
                double score = hit.path("_score").asDouble();
                results.add(new ScoredResult(chunkId, documentId,
                        source.path("documentTitle").asText(""),
                        source.path("content").asText(""),
                        score));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析 Elasticsearch 检索结果", ex);
        }
        return results;
    }

    /** 测试里确认查询 JSON 带上了知识库过滤。 */
    public static String searchBodyJson(UUID knowledgeBaseId, String tokens, int limit, ObjectMapper objectMapper) {
        try {
            ObjectNode node = objectMapper.valueToTree(searchBody(knowledgeBaseId, tokens, limit));
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
