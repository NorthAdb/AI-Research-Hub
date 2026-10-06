package com.aireshub.chat;

import com.aireshub.common.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RAG 问答(路线图 Phase 5:POST /api/chat 的知识库维度实现)。 */
@RestController
public class ChatController {

    public record AskRequest(@NotBlank @Size(max = 2000) String question, UUID sessionId) {
    }

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/api/knowledge-bases/{kbId}/chat")
    public ChatService.ChatResponse ask(@PathVariable UUID kbId,
                                        @Valid @RequestBody AskRequest request,
                                        @AuthenticationPrincipal AuthUser user) {
        return chatService.ask(user.id(), kbId, request.sessionId(), request.question());
    }

    @GetMapping("/api/knowledge-bases/{kbId}/chat/latest")
    public ResponseEntity<ChatService.SessionHistory> latest(@PathVariable UUID kbId,
                                                             @AuthenticationPrincipal AuthUser user) {
        ChatService.SessionHistory history = chatService.latest(user.id(), kbId);
        return history == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(history);
    }

    @GetMapping("/api/chat/sessions/{sessionId}/messages")
    public List<ChatMessageView> history(@PathVariable UUID sessionId,
                                         @AuthenticationPrincipal AuthUser user) {
        return chatService.history(user.id(), sessionId).stream()
                .map(m -> new ChatMessageView(m.getRole(), m.getContent()))
                .toList();
    }

    public record ChatMessageView(String role, String content) {
    }
}
