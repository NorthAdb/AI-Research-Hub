package com.aireshub.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Phase 9(Roadmap)/实时进度:文档状态变化通过 SSE 推给前端。
 * 本地环境替代 Supabase Realtime;接云端后可换 Realtime Channel,事件语义不变。
 */
@Service
public class DocumentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventPublisher.class);

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public DocumentEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record StatusEvent(UUID documentId, String title, String status, String errorMessage) {
    }

    public SseEmitter subscribe(UUID kbId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.computeIfAbsent(kbId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> emitters.getOrDefault(kbId, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(UUID kbId, StatusEvent event) {
        List<SseEmitter> subscribers = emitters.get(kbId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(subscribers)) {
            try {
                emitter.send(SseEmitter.event().name("document-status").data(json,
                        org.springframework.http.MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                subscribers.remove(emitter);
            }
        }
    }
}
