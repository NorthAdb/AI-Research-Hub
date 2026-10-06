package com.aireshub.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 每次检索的过程留痕(路线图 §13),后续可用于评估。 */
@Entity
@Table(name = "search_logs")
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(nullable = false, columnDefinition = "text")
    private String query;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dense_results")
    private String denseResults;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sparse_results")
    private String sparseResults;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fused_results")
    private String fusedResults;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reranked_results")
    private String rerankedResults;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getDenseResults() {
        return denseResults;
    }

    public void setDenseResults(String denseResults) {
        this.denseResults = denseResults;
    }

    public String getSparseResults() {
        return sparseResults;
    }

    public void setSparseResults(String sparseResults) {
        this.sparseResults = sparseResults;
    }

    public String getFusedResults() {
        return fusedResults;
    }

    public void setFusedResults(String fusedResults) {
        this.fusedResults = fusedResults;
    }

    public String getRerankedResults() {
        return rerankedResults;
    }

    public void setRerankedResults(String rerankedResults) {
        this.rerankedResults = rerankedResults;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }
}
