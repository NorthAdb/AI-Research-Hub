package com.aireshub.search;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchLogRepository extends JpaRepository<SearchLog, UUID> {

    List<SearchLog> findByUserIdAndKnowledgeBaseIdOrderByCreatedAtDesc(
            UUID userId, UUID knowledgeBaseId, Pageable pageable);
}
