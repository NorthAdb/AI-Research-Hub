package com.aireshub.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByKnowledgeBaseIdOrderByCreatedAtDesc(UUID knowledgeBaseId);

    Optional<Document> findByKnowledgeBaseIdAndId(UUID knowledgeBaseId, UUID id);
}
