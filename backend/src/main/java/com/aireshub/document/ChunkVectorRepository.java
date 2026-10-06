package com.aireshub.document;

import com.aireshub.search.ScoredResult;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * pgvector / FTS 原生 SQL 访问层。
 * embedding/fts 列不进 JPA 实体,统一在这里读写。
 */
@Repository
public class ChunkVectorRepository {

    private final EntityManager em;

    public ChunkVectorRepository(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public void setEmbedding(UUID chunkId, String vectorText) {
        em.createNativeQuery("update document_chunks set embedding = cast(:qv as vector) where id = :id")
                .setParameter("qv", vectorText)
                .setParameter("id", chunkId)
                .executeUpdate();
    }

    @Transactional
    public void setFts(UUID chunkId, String bigramText) {
        em.createNativeQuery("update document_chunks set fts = to_tsvector('simple', :txt) where id = :id")
                .setParameter("txt", bigramText)
                .setParameter("id", chunkId)
                .executeUpdate();
    }

    /** 余弦相似度稠密检索(pgvector HNSW)。 */
    @SuppressWarnings("unchecked")
    public List<ScoredResult> denseSearch(UUID kbId, String vectorText, int limit) {
        String sql = """
                select c.id, c.document_id, d.title, c.content,
                       1 - (c.embedding <=> cast(:qv as vector)) as score
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.knowledge_base_id = :kbId and c.embedding is not null
                order by c.embedding <=> cast(:qv as vector)
                limit :k
                """;
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("qv", vectorText)
                .setParameter("kbId", kbId)
                .setParameter("k", limit)
                .getResultList();
        return rows.stream().map(r -> new ScoredResult(
                asUuid(r[0]), asUuid(r[1]), String.valueOf(r[2]),
                String.valueOf(r[3]), ((Number) r[4]).doubleValue())).toList();
    }

    /** BM25-ish 稀疏检索(PostgreSQL FTS,ts_rank)。 */
    @SuppressWarnings("unchecked")
    public List<ScoredResult> sparseSearch(UUID kbId, String tsquery, int limit) {
        String sql = """
                select c.id, c.document_id, d.title, c.content,
                       ts_rank(c.fts, to_tsquery('simple', :q)) as score
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.knowledge_base_id = :kbId
                  and c.fts @@ to_tsquery('simple', :q)
                order by score desc
                limit :k
                """;
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("q", tsquery)
                .setParameter("kbId", kbId)
                .setParameter("k", limit)
                .getResultList();
        return rows.stream().map(r -> new ScoredResult(
                asUuid(r[0]), asUuid(r[1]), String.valueOf(r[2]),
                String.valueOf(r[3]), ((Number) r[4]).doubleValue())).toList();
    }

    private static UUID asUuid(Object value) {
        return UUID.fromString(String.valueOf(value));
    }
}
