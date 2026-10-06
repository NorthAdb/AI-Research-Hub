package com.aireshub.search;

import com.aireshub.ai.TextTokenizer;
import com.aireshub.document.ChunkVectorRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 默认稀疏检索：应用侧 bigram 写入 tsvector，查询用 OR tsquery。 */
@Component
@ConditionalOnProperty(name = "app.search.sparse", havingValue = "postgres", matchIfMissing = true)
public class PostgresSparseSearch implements SparseSearch {

    private final ChunkVectorRepository vectorRepository;

    public PostgresSparseSearch(ChunkVectorRepository vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public List<ScoredResult> search(UUID knowledgeBaseId, String query, int limit) {
        String tsquery = TextTokenizer.tsqueryOr(query);
        if (tsquery.isEmpty()) {
            return List.of();
        }
        return vectorRepository.sparseSearch(knowledgeBaseId, tsquery, limit);
    }

    @Override
    public void indexChunk(UUID knowledgeBaseId, UUID documentId, String documentTitle,
                           UUID chunkId, String content) {
        // 全文列在摄入管线里和向量一起写入。
    }

    @Override
    public void deleteDocument(UUID documentId) {
        // 切片随文档外键级联删除。
    }
}
