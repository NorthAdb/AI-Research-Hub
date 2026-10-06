package com.aireshub.search;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchJudgmentRepository extends JpaRepository<SearchJudgment, UUID> {

    List<SearchJudgment> findByKnowledgeBaseIdOrderByCreatedAtAsc(UUID knowledgeBaseId);

    boolean existsByKnowledgeBaseIdAndQueryAndDocumentId(UUID knowledgeBaseId, String query, UUID documentId);
}
