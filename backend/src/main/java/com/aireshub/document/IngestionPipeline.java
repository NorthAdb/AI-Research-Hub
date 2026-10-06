package com.aireshub.document;

import com.aireshub.ai.EmbeddingService;
import com.aireshub.ai.TextTokenizer;
import com.aireshub.chat.QueryCacheService;
import com.aireshub.search.SparseSearch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 摄入管线(路线图 Phase 5):解析 → 清洗 → 切分 → Embedding → 入库索引。
 * 状态机:UPLOADED → PROCESSING → INDEXED / FAILED(+error_message)。
 * Phase 5 由 Kafka Worker 调用,Phase 2~4 由 InlineIngestionTrigger 调用。
 */
@Service
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChunkVectorRepository vectorRepository;
    private final TextExtractionService extractionService;
    private final TextChunker chunker;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;
    private final DocumentEventPublisher eventPublisher;
    private final DocumentFailureMarker failureMarker;
    private final QueryCacheService queryCacheService;
    private final SparseSearch sparseSearch;

    public IngestionPipeline(DocumentRepository documentRepository,
                             DocumentChunkRepository chunkRepository,
                             ChunkVectorRepository vectorRepository,
                             TextExtractionService extractionService,
                             TextChunker chunker,
                             EmbeddingService embeddingService,
                             ObjectMapper objectMapper,
                             DocumentEventPublisher eventPublisher,
                             DocumentFailureMarker failureMarker,
                             QueryCacheService queryCacheService,
                             SparseSearch sparseSearch,
                             ObjectStorage objectStorage) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorRepository = vectorRepository;
        this.extractionService = extractionService;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.failureMarker = failureMarker;
        this.queryCacheService = queryCacheService;
        this.sparseSearch = sparseSearch;
        this.objectStorage = objectStorage;
    }

    @Async("pipelineExecutor")
    @Transactional
    public void processAsync(UUID documentId) {
        try {
            process(documentId);
        } catch (Exception ex) {
            log.error("文档处理失败: {}", documentId, ex);
        }
    }

    /** Kafka Worker 直接调用:代理路径上有事务;inline 路径加入 processAsync 的事务。 */
    @Transactional
    public void process(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setStatus(Document.STATUS_PROCESSING);
        doc.setErrorMessage(null);
        documentRepository.save(doc);
        emitStatus(doc);
        try {
            byte[] bytes = objectStorage.read(doc.getStoragePath());
            List<TextExtractionService.PageText> pages =
                    extractionService.extract(doc.getMimeType(), bytes);
            List<TextChunker.ChunkRecord> chunks = chunker.chunk(pages);

            chunkRepository.deleteByDocumentId(doc.getId());
            sparseSearch.deleteDocument(doc.getId());
            for (TextChunker.ChunkRecord chunk : chunks) {
                DocumentChunk entity = new DocumentChunk();
                entity.setDocumentId(doc.getId());
                entity.setChunkIndex(chunk.index());
                entity.setContent(chunk.content());
                entity.setMetadata(objectMapper.writeValueAsString(Map.of(
                        "source", doc.getFilename(),
                        "page", chunk.page())));
                chunkRepository.saveAndFlush(entity);
                vectorRepository.setEmbedding(entity.getId(),
                        toVectorString(embeddingService.embed(chunk.content())));
                vectorRepository.setFts(entity.getId(), TextTokenizer.bigramsForFts(chunk.content()));
                sparseSearch.indexChunk(doc.getKnowledgeBaseId(), doc.getId(), doc.getTitle(),
                        entity.getId(), chunk.content());
            }
            doc.setStatus(Document.STATUS_INDEXED);
            doc.setErrorMessage(null);
            documentRepository.save(doc);
            emitStatus(doc);
            UUID kbId = doc.getKnowledgeBaseId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        queryCacheService.evictKnowledgeBase(kbId);
                    }
                });
            } else {
                queryCacheService.evictKnowledgeBase(kbId);
            }
            log.info("文档索引完成: {} ({} chunks)", doc.getTitle(), chunks.size());
        } catch (Exception ex) {
            // 半截切片随本事务回滚。失败状态等锁释放后再写,避免和当前事务抢同一行。
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            failureMarker.markFailed(documentId, message);
                        }
                    }
                });
            } else {
                failureMarker.markFailed(documentId, message);
            }
            log.error("文档处理异常: {}", documentId, ex);
        }
    }

    private void emitStatus(Document doc) {
        try {
            eventPublisher.publish(doc.getKnowledgeBaseId(), new DocumentEventPublisher.StatusEvent(
                    doc.getId(), doc.getTitle(), doc.getStatus(), doc.getErrorMessage()));
        } catch (Exception ignored) {
            // SSE 推送失败不影响处理流程
        }
    }

    /** float[] → pgvector 文本格式 '[0.12,0.33,...]' */
    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
