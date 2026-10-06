package com.aireshub.document;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把失败状态写到独立事务里。
 * 摄入主事务会回滚半截切片,不能在同一条连接上再改同一行,否则会互相等锁。
 */
@Service
public class DocumentFailureMarker {

    private final DocumentRepository documentRepository;
    private final DocumentEventPublisher eventPublisher;

    public DocumentFailureMarker(DocumentRepository documentRepository,
                                 DocumentEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentId, String message) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setStatus(Document.STATUS_FAILED);
        doc.setErrorMessage(message == null || message.isBlank() ? "文档处理失败" : message);
        doc.setRetryCount(doc.getRetryCount() + 1);
        documentRepository.save(doc);
        eventPublisher.publish(doc.getKnowledgeBaseId(), new DocumentEventPublisher.StatusEvent(
                doc.getId(), doc.getTitle(), doc.getStatus(), doc.getErrorMessage()));
    }
}
