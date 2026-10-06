package com.aireshub.knowledge;

import com.aireshub.common.ApiException;
import com.aireshub.common.AuthUser;
import com.aireshub.document.Document;
import com.aireshub.document.DocumentRepository;
import com.aireshub.document.DocumentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepository,
                                DocumentRepository documentRepository,
                                DocumentService documentService) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
    }

    @Transactional
    public KnowledgeBaseController.KbView create(AuthUser owner,
                                                 KnowledgeBaseController.CreateKbRequest request) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setOwnerId(owner.id());
        kb.setName(request.name().trim());
        kb.setDescription(request.description());
        Instant now = Instant.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kbRepository.save(kb);
        return KnowledgeBaseController.KbView.from(kb);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseController.KbView> listOwned(UUID ownerId) {
        return kbRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(KnowledgeBaseController.KbView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBase getOwned(UUID id, UUID ownerId) {
        return kbRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
    }

    /**
     * 删除知识库。数据库行由外键 ON DELETE CASCADE 级联删除;
     * 已上传的文档文件需要先查出来,删库后逐一清理磁盘文件。
     */
    @Transactional
    public void deleteOwned(UUID id, UUID ownerId) {
        KnowledgeBase kb = getOwned(id, ownerId);
        List<Document> documents = documentRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kb.getId());
        kbRepository.delete(kb);
        for (Document doc : documents) {
            documentService.deleteFileQuietly(doc.getStoragePath());
            documentService.forgetSparseAfterCommit(doc.getId());
        }
        documentService.evictAfterCommit(kb.getId());
    }
}
