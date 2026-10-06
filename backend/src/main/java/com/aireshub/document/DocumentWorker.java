package com.aireshub.document;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 5 文档处理 Worker:消费 document.process 事件,同步执行摄入管线。
 * 失败自动重试(最多 3 次尝试,retry_count 落库),仍失败则终态 FAILED。
 */
@Component
@ConditionalOnProperty(name = "app.pipeline.mode", havingValue = "kafka")
public class DocumentWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentWorker.class);
    private static final int MAX_ATTEMPTS = 3;

    private final IngestionPipeline pipeline;
    private final DocumentRepository documentRepository;

    public DocumentWorker(IngestionPipeline pipeline, DocumentRepository documentRepository) {
        this.pipeline = pipeline;
        this.documentRepository = documentRepository;
    }

    @KafkaListener(topics = "${app.kafka.topic}", groupId = "ai-research-hub-worker")
    public void onDocumentProcess(String documentId) {
        UUID id = UUID.fromString(documentId);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            pipeline.process(id);
            Document doc = documentRepository.findById(id).orElse(null);
            if (doc == null) {
                return;
            }
            if (Document.STATUS_INDEXED.equals(doc.getStatus())) {
                return;
            }
            log.warn("文档第 {} 次处理未成功(status={}): {}", attempt, doc.getStatus(), doc.getTitle());
        }
        Document doc = documentRepository.findById(id).orElse(null);
        log.error("文档处理最终失败(status={}, retryCount={}): {}",
                doc == null ? "?" : doc.getStatus(),
                doc == null ? "?" : doc.getRetryCount(),
                doc == null ? documentId : doc.getTitle());
    }
}
