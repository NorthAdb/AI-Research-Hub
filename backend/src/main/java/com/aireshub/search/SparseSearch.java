package com.aireshub.search;

import java.util.List;
import java.util.UUID;

/**
 * 稀疏检索。默认是 PostgreSQL 全文；SEARCH_SPARSE=elasticsearch 时换成 Elasticsearch。
 * 向量检索始终留在 PostgreSQL，不进这里。
 */
public interface SparseSearch {

    String name();

    List<ScoredResult> search(UUID knowledgeBaseId, String query, int limit);

    void indexChunk(UUID knowledgeBaseId, UUID documentId, String documentTitle, UUID chunkId, String content);

    void deleteDocument(UUID documentId);
}
