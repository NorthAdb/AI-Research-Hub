package com.aireshub.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findByIdAndUserId(UUID id, UUID userId);

    List<ChatSession> findByUserIdAndKnowledgeBaseIdOrderByCreatedAtDesc(UUID userId, UUID kbId);
}
