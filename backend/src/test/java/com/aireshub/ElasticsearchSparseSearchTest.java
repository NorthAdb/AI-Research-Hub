package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aireshub.search.ElasticsearchSparseSearch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ElasticsearchSparseSearchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID kbId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID docId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final UUID chunkId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void searchBodyAlwaysFiltersByKnowledgeBase() throws Exception {
        String json = ElasticsearchSparseSearch.searchBodyJson(kbId, "混合 密钥", 5, objectMapper);
        assertTrue(json.contains(kbId.toString()));
        assertTrue(json.contains("\"filter\""));
        assertTrue(json.contains("混合 密钥"));
    }

    @Test
    void parsesHitsIntoScoredResults() {
        String json = """
                {"hits":{"hits":[{"_id":"%s","_score":2.5,"_source":{"documentId":"%s","documentTitle":"笔记","content":"正文"}}]}}
                """.formatted(chunkId, docId);
        var hits = ElasticsearchSparseSearch.parseHits(json, objectMapper);
        assertEquals(1, hits.size());
        assertEquals(chunkId, hits.get(0).chunkId());
        assertEquals(docId, hits.get(0).documentId());
        assertEquals("笔记", hits.get(0).documentTitle());
        assertEquals(2.5, hits.get(0).score());
    }

    @Test
    void searchPostsTheFilterToElasticsearch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://es:9200");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ElasticsearchSparseSearch search = new ElasticsearchSparseSearch(objectMapper, builder.build(), false);
        server.expect(request -> assertTrue(request.getURI().getPath().endsWith("/arh-chunks/_search")))
                .andExpect(request -> assertEquals(HttpMethod.POST, request.getMethod()))
                .andExpect(request -> assertTrue(request.getBody().toString().contains(kbId.toString())))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"hits":{"hits":[{"_id":"%s","_score":1.0,"_source":{"documentId":"%s","documentTitle":"笔记","content":"正文"}}]}}
                        """.formatted(chunkId, docId),
                        MediaType.APPLICATION_JSON));

        var hits = search.search(kbId, "混合密钥", 5);
        assertEquals(1, hits.size());
        server.verify();
    }
}
