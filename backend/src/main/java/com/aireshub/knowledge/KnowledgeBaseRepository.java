package com.aireshub.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    List<KnowledgeBase> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<KnowledgeBase> findByIdAndOwnerId(UUID id, UUID ownerId);
}
