package com.aireshub.document;

import com.aireshub.chat.QueryCacheService;
import com.aireshub.common.ApiException;
import com.aireshub.search.SparseSearch;
import com.aireshub.knowledge.KnowledgeBase;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 第一版允许的类型;Phase 5 (RAG) 的 Parser 将按类型分发解析。 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "text/markdown", "text/plain");

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final IngestionTrigger ingestionTrigger;
    private final QueryCacheService queryCacheService;
    private final SparseSearch sparseSearch;
    private final ObjectStorage objectStorage;

    public DocumentService(DocumentRepository documentRepository,
                           KnowledgeBaseRepository kbRepository,
                           IngestionTrigger ingestionTrigger,
                           QueryCacheService queryCacheService,
                           SparseSearch sparseSearch,
                           ObjectStorage objectStorage) {
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
        this.ingestionTrigger = ingestionTrigger;
        this.queryCacheService = queryCacheService;
        this.sparseSearch = sparseSearch;
        this.objectStorage = objectStorage;
    }

    /**
     * 浏览器上传 Markdown 时经常给 application/octet-stream 或空类型。
     * 已声明的 PDF / Markdown / 纯文本优先;否则按扩展名判断。无法识别时返回 null。
     */
    public static String resolveMime(String contentType, String filename) {
        String mime = contentType == null ? "" : contentType.toLowerCase().split(";", 2)[0].trim();
        if (ALLOWED_TYPES.contains(mime)) {
            return mime;
        }
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        return null;
    }

    public record DocumentView(UUID id, UUID knowledgeBaseId, String title, String filename,
                               String mimeType, Long sizeBytes, String status,
                               String errorMessage, Instant createdAt) {

        static DocumentView from(Document doc) {
            return new DocumentView(doc.getId(), doc.getKnowledgeBaseId(), doc.getTitle(),
                    doc.getFilename(), doc.getMimeType(), doc.getSizeBytes(),
                    doc.getStatus(), doc.getErrorMessage(), doc.getCreatedAt());
        }
    }

    @Transactional
    public DocumentView upload(UUID kbId, UUID ownerId, MultipartFile file, String title) {
        KnowledgeBase kb = ownedKb(kbId, ownerId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传文件为空");
        }
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String mime = resolveMime(file.getContentType(), originalName);
        if (mime == null) {
            throw ApiException.badRequest("仅支持 PDF / Markdown / 纯文本文件,当前类型: "
                    + (file.getContentType() == null ? "未知" : file.getContentType()));
        }

        String original = java.nio.file.Path.of(originalName).getFileName().toString();
        try {
            String storagePath = objectStorage.write(kb.getId(), original, file.getBytes());

            Document doc = new Document();
            doc.setKnowledgeBaseId(kb.getId());
            doc.setTitle(title == null || title.isBlank() ? original : title.trim());
            doc.setFilename(original);
            doc.setStoragePath(storagePath);
            doc.setMimeType(mime);
            doc.setSizeBytes(file.getSize());
            doc.setStatus(Document.STATUS_UPLOADED);
            Instant now = Instant.now();
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            documentRepository.save(doc);
            // 事务提交后再触发异步摄入,否则管线线程读不到未提交的文档行
            triggerAfterCommit(doc.getId());
            return DocumentView.from(doc);
        } catch (java.io.IOException | IllegalStateException ex) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件保存失败: " + ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentView> listByKb(UUID kbId, UUID ownerId) {
        ownedKb(kbId, ownerId);
        return documentRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId).stream()
                .map(DocumentView::from)
                .toList();
    }

    @Transactional
    public void deleteOwned(UUID docId, UUID ownerId) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在"));
        UUID kbId = doc.getKnowledgeBaseId();
        ownedKb(kbId, ownerId);
        documentRepository.delete(doc);
        deleteFileQuietly(doc.getStoragePath());
        evictAfterCommit(kbId);
        forgetSparseAfterCommit(doc.getId());
    }

    /** 文档从库里删掉之后，稀疏索引里的切片也要删。PostgreSQL 模式是空操作。 */
    public void forgetSparseAfterCommit(UUID documentId) {
        Runnable forget = () -> {
            try {
                sparseSearch.deleteDocument(documentId);
            } catch (Exception ex) {
                log.warn("稀疏索引删除失败(文档已从数据库删除): {}", documentId, ex);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            forget.run();
                        }
                    });
        } else {
            forget.run();
        }
    }

    /** 文档删除或知识库删除后,旧问答不能再命中。 */
    public void evictAfterCommit(UUID kbId) {
        Runnable evict = () -> queryCacheService.evictKnowledgeBase(kbId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict.run();
                        }
                    });
        } else {
            evict.run();
        }
    }

    /** 事务提交后再触发;无事务上下文时立即触发。 */
    private void triggerAfterCommit(UUID documentId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            ingestionTrigger.trigger(documentId);
                        }
                    });
        } else {
            ingestionTrigger.trigger(documentId);
        }
    }

    private KnowledgeBase ownedKb(UUID kbId, UUID ownerId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
        if (!kb.getOwnerId().equals(ownerId)) {
            throw ApiException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    /** 按相对路径删除磁盘文件,失败仅记日志(数据库行已删,文件可后台清理)。 */
    public void deleteFileQuietly(String relativePath) {
        try {
            objectStorage.delete(relativePath);
        } catch (Exception ex) {
            log.warn("删除文件失败: {}", relativePath, ex);
        }
    }
}
