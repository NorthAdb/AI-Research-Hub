package com.aireshub.document;

import com.aireshub.common.AuthUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentEventPublisher eventPublisher;
    private final com.aireshub.auth.JwtService jwtService;
    private final com.aireshub.auth.UserRepository userRepository;
    private final com.aireshub.knowledge.KnowledgeBaseRepository kbRepository;

    public DocumentController(DocumentService documentService,
                              DocumentEventPublisher eventPublisher,
                              com.aireshub.auth.JwtService jwtService,
                              com.aireshub.auth.UserRepository userRepository,
                              com.aireshub.knowledge.KnowledgeBaseRepository kbRepository) {
        this.documentService = documentService;
        this.eventPublisher = eventPublisher;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.kbRepository = kbRepository;
    }

    /**
     * Phase 9 SSE 实时进度。EventSource 无法携带 Authorization 头,
     * 允许 ?token= 查询参数(与 Supabase Realtime 的 token 用法一致)。
     */
    @org.springframework.web.bind.annotation.GetMapping(value = "/knowledge-bases/{kbId}/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@PathVariable UUID kbId,
            @org.springframework.web.bind.annotation.RequestParam("token") String token) {
        java.util.UUID userId;
        try {
            userId = java.util.UUID.fromString(jwtService.parse(token).getSubject());
        } catch (Exception e) {
            throw com.aireshub.common.ApiException.unauthorized("token 无效");
        }
        var kb = kbRepository.findById(kbId)
                .filter(k -> k.getOwnerId().equals(userId))
                .orElseThrow(() -> com.aireshub.common.ApiException.forbidden("无权订阅该知识库"));
        return eventPublisher.subscribe(kb.getId());
    }

    /** 上传文档:文件落本地存储(模拟 Supabase Storage),DB 只存 metadata,status=UPLOADED。 */
    @PostMapping("/knowledge-bases/{kbId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentView upload(@PathVariable UUID kbId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "title", required = false) String title,
                                               @AuthenticationPrincipal AuthUser user) {
        return documentService.upload(kbId, user.id(), file, title);
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public List<DocumentService.DocumentView> listByKb(@PathVariable UUID kbId,
                                                       @AuthenticationPrincipal AuthUser user) {
        return documentService.listByKb(kbId, user.id());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AuthUser user) {
        documentService.deleteOwned(id, user.id());
    }
}
