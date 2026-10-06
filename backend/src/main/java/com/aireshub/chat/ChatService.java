package com.aireshub.chat;

import com.aireshub.ai.LlmService;
import com.aireshub.common.ApiException;
import com.aireshub.knowledge.KnowledgeBase;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import com.aireshub.search.SearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 问答(路线图 Phase 5):Hybrid 检索 → 上下文 → LLM → 回答 + Citation。
 * 会话与消息按路线图表结构持久化。
 */
@Service
public class ChatService {

    public record Citation(int n, UUID documentId, String documentTitle, UUID chunkId,
                           String snippet, double score) {
    }

    public record ChatResponse(UUID sessionId, String answer, String provider,
                               List<Citation> citations, SearchService.Trace trace,
                               long latencyMs, boolean fromCache) {
    }

    private final SearchService searchService;
    private final LlmService llmService;
    private final KnowledgeBaseRepository kbRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final QueryCacheService cacheService;
    private final int topK;

    public ChatService(SearchService searchService,
                       LlmService llmService,
                       KnowledgeBaseRepository kbRepository,
                       ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       QueryCacheService cacheService,
                       @Value("${app.rag.top-k:5}") int topK) {
        this.searchService = searchService;
        this.llmService = llmService;
        this.kbRepository = kbRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.cacheService = cacheService;
        this.topK = topK;
    }

    @Transactional
    public ChatResponse ask(UUID userId, UUID kbId, UUID sessionId, String question) {
        long start = System.currentTimeMillis();
        KnowledgeBase kb = ownedKb(kbId, userId);

        // Phase 7 查询缓存:命中则跳过检索与 LLM
        QueryCacheService.Lookup lookup = cacheService.get(kb.getId(), question);
        if (lookup.answer() != null) {
            QueryCacheService.CachedAnswer cached = lookup.answer();
            ChatSession session = resolveSession(userId, kb.getId(), sessionId, question);
            persistTurn(session.getId(), question, cached.answer());
            return new ChatResponse(session.getId(), cached.answer(), cached.provider(),
                    cached.citations(), cached.trace(), cached.latencyMs(), true);
        }

        SearchService.SearchOutcome outcome =
                searchService.search(userId, kb.getId(), question, topK);

        List<LlmService.CitedContext> contexts = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        int n = 1;
        for (var result : outcome.results()) {
            contexts.add(new LlmService.CitedContext(n, result.content()));
            citations.add(new Citation(n, result.documentId(), result.documentTitle(),
                    result.chunkId(), snippet(result.content()), result.score()));
            n++;
        }

        LlmService.LlmResult llm = llmService.answer(question, contexts);
        long latency = System.currentTimeMillis() - start;

        cacheService.put(lookup.key(), new QueryCacheService.CachedAnswer(
                llm.answer(), llm.provider(), citations, outcome.trace(), latency));

        ChatSession session = resolveSession(userId, kb.getId(), sessionId, question);
        persistTurn(session.getId(), question, llm.answer());

        return new ChatResponse(session.getId(), llm.answer(), llm.provider(),
                citations, outcome.trace(), latency, false);
    }

    private void persistTurn(UUID sessionId, String question, String answer) {
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(question);
        messageRepository.save(userMessage);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(answer);
        messageRepository.save(assistantMessage);
    }

    public record MessageView(String role, String content) {
    }

    public record SessionHistory(UUID sessionId, List<MessageView> messages) {
    }

    /** 这个知识库里最近一次问答。没有会话时返回 null。 */
    @Transactional(readOnly = true)
    public SessionHistory latest(UUID userId, UUID kbId) {
        ownedKb(kbId, userId);
        List<ChatSession> sessions =
                sessionRepository.findByUserIdAndKnowledgeBaseIdOrderByCreatedAtDesc(userId, kbId);
        if (sessions.isEmpty()) {
            return null;
        }
        ChatSession session = sessions.get(0);
        List<MessageView> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(message -> new MessageView(message.getRole(), message.getContent()))
                .toList();
        return new SessionHistory(session.getId(), messages);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(UUID userId, UUID sessionId) {
        ChatSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("会话不存在或无权访问"));
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }

    private ChatSession resolveSession(UUID userId, UUID kbId, UUID sessionId, String question) {
        if (sessionId != null) {
            ChatSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> ApiException.notFound("会话不存在或无权访问"));
            if (session.getKnowledgeBaseId() == null || !session.getKnowledgeBaseId().equals(kbId)) {
                throw ApiException.forbidden("会话不属于这个知识库");
            }
            return session;
        }
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setKnowledgeBaseId(kbId);
        session.setTitle(question.length() > 30 ? question.substring(0, 30) + "…" : question);
        return sessionRepository.save(session);
    }

    private KnowledgeBase ownedKb(UUID kbId, UUID userId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
        if (!kb.getOwnerId().equals(userId)) {
            throw ApiException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    private String snippet(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "…";
    }
}
