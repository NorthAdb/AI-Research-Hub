package com.aireshub.agent;

import com.aireshub.ai.LlmService;
import com.aireshub.ai.TextTokenizer;
import com.aireshub.chat.ChatService;
import com.aireshub.chat.ChatService.Citation;
import com.aireshub.common.ApiException;
import com.aireshub.document.Document;
import com.aireshub.document.DocumentChunk;
import com.aireshub.document.DocumentChunkRepository;
import com.aireshub.document.DocumentRepository;
import com.aireshub.knowledge.KnowledgeBase;
import com.aireshub.knowledge.KnowledgeBaseRepository;
import com.aireshub.search.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agentic RAG(路线图 Phase 11 / §18-19):
 * Agent 循环 ≤ max-steps 步;工具 ≤ 5 个;全程 agent_runs / agent_steps 留痕。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    public record AgentStepView(int stepNo, String tool, Map<String, Object> args,
                                String resultSummary, Integer latencyMs) {
    }

    public record AgentResponse(UUID runId, String answer, List<Citation> citations,
                                List<AgentStepView> steps, long latencyMs) {
    }

    private final SearchService searchService;
    private final LlmService llmService;
    private final AgentBrain brain;
    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ObjectMapper objectMapper;
    private final int topK;
    private final int maxSteps;

    public AgentService(SearchService searchService,
                        LlmService llmService,
                        AgentBrain brain,
                        KnowledgeBaseRepository kbRepository,
                        DocumentRepository documentRepository,
                        DocumentChunkRepository chunkRepository,
                        AgentRunRepository runRepository,
                        AgentStepRepository stepRepository,
                        ObjectMapper objectMapper,
                        @Value("${app.rag.top-k:5}") int topK,
                        @Value("${app.agent.max-steps:6}") int maxSteps) {
        this.searchService = searchService;
        this.llmService = llmService;
        this.brain = brain;
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
        this.topK = topK;
        this.maxSteps = maxSteps;
    }

    @Transactional
    public AgentResponse run(UUID userId, UUID kbId, String question) {
        long start = System.currentTimeMillis();
        KnowledgeBase kb = ownedKb(kbId, userId);

        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setKnowledgeBaseId(kb.getId());
        run.setInput(question);
        run = runRepository.save(run);

        List<Citation> citations = new ArrayList<>();
        List<AgentBrain.StepView> history = new ArrayList<>();
        List<LlmService.CitedContext> contexts = new ArrayList<>();
        int nextCitation = 1;
        String finalAnswer = null;
        int stepNo = 0;

        while (stepNo < maxSteps) {
            AgentBrain.Decision decision = brain.decide(question, List.copyOf(history));

            if (decision.finalAnswer().isPresent()) {
                finalAnswer = decision.finalAnswer().get();
                break;
            }
            if (decision.toolCall().isEmpty()) {
                break;
            }

            AgentBrain.ToolCall call = decision.toolCall().get();
            long toolStart = System.currentTimeMillis();
            Map<String, Object> data = new HashMap<>();
            String summary = "";
            List<Citation> toolCitations = List.of();
            try {
                ToolResult result = executeTool(userId, kb, call, nextCitation);
                summary = result.summary();
                data = result.data();
                toolCitations = result.citations();
                for (int i = 0; i < result.contexts().size(); i++) {
                    contexts.add(new LlmService.CitedContext(nextCitation + i,
                            result.contexts().get(i)));
                }
                nextCitation += result.contexts().size();
                citations.addAll(toolCitations);
            } catch (Exception ex) {
                summary = "工具执行失败: " + ex.getMessage();
            }
            int latency = (int) (System.currentTimeMillis() - toolStart);
            stepNo++;

            AgentStep step = new AgentStep();
            step.setRunId(run.getId());
            step.setStepNo(stepNo);
            step.setTool(call.tool());
            step.setArgs(writeJson(call.args()));
            step.setResultSummary(summary);
            step.setLatencyMs(latency);
            stepRepository.save(step);

            history.add(new AgentBrain.StepView(stepNo, call.tool(), call.args(), summary, data));
        }

        if (finalAnswer == null) {
            finalAnswer = llmService.answer(question, contexts).answer();
        }

        run.setFinalAnswer(finalAnswer);
        run.setLatencyMs((int) (System.currentTimeMillis() - start));
        run.setTokenUsage(writeJson(Map.of("steps", stepNo, "toolCalls", stepNo,
                "contexts", contexts.size())));
        runRepository.save(run);

        List<AgentStepView> stepViews = history.stream()
                .map(s -> new AgentStepView(s.stepNo(), s.tool(), s.args(), s.resultSummary(), null))
                .toList();
        return new AgentResponse(run.getId(), finalAnswer, citations, stepViews, run.getLatencyMs());
    }

    @Transactional(readOnly = true)
    public AgentRunRunView getRun(UUID userId, UUID runId) {
        AgentRun run = runRepository.findById(runId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Agent 运行记录不存在或无权访问"));
        List<AgentStep> steps = stepRepository.findByRunIdOrderByStepNo(runId);
        return new AgentRunRunView(run, steps);
    }

    public record AgentRunRunView(AgentRun run, List<AgentStep> steps) {
    }

    private record ToolResult(String summary, Map<String, Object> data,
                              List<String> contexts, List<Citation> citations) {
    }

    /** 工具注册表(路线图要求 ≤ 5 个):search / get_document / list_documents / summarize_document。 */
    private ToolResult executeTool(UUID userId, KnowledgeBase kb, AgentBrain.ToolCall call,
                                   int citationStart) {
        return switch (call.tool()) {
            case "search_knowledge_base" -> {
                String query = str(call.args(), "query", "");
                if (query.isBlank()) {
                    yield new ToolResult("query 为空,未执行检索", Map.of(), List.of(), List.of());
                }
                var outcome = searchService.search(userId, kb.getId(), query, topK);
                List<String> contexts = outcome.results().stream().map(r -> r.content()).toList();
                List<Citation> citations = new ArrayList<>();
                StringBuilder summary = new StringBuilder();
                summary.append("检索「").append(query).append("」命中 ")
                        .append(outcome.results().size()).append(" 个片段\n");
                int n = citationStart;
                for (var r : outcome.results()) {
                    citations.add(new Citation(n, r.documentId(), r.documentTitle(),
                            r.chunkId(), snippet(r.content()), r.score()));
                    summary.append("[").append(n).append("] ").append(r.documentTitle())
                            .append("：").append(snippet(r.content())).append("\n");
                    n++;
                }
                Map<String, Object> data = new HashMap<>();
                data.put("firstDocumentId", outcome.results().isEmpty()
                        ? null : outcome.results().get(0).documentId().toString());
                data.put("hitCount", outcome.results().size());
                yield new ToolResult(summary.toString().trim(), data, contexts, citations);
            }
            case "get_document" -> {
                Document doc = ownedDocument(userId, str(call.args(), "document_id", ""));
                List<DocumentChunk> chunks =
                        chunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());
                List<String> contexts = chunks.stream().limit(3).map(DocumentChunk::getContent).toList();
                StringBuilder summary = new StringBuilder();
                summary.append("文档「").append(doc.getTitle()).append("」状态 ").append(doc.getStatus())
                        .append("，共 ").append(chunks.size()).append(" 个切片。前 ")
                        .append(contexts.size()).append(" 段：\n");
                for (String text : contexts) {
                    summary.append(snippet(text)).append("\n");
                }
                yield new ToolResult(summary.toString().trim(),
                        Map.of("chunkCount", chunks.size(), "documentId", doc.getId().toString()),
                        contexts, List.of());
            }
            case "list_documents" -> {
                List<Document> docs = documentRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kb.getId());
                StringBuilder sb = new StringBuilder();
                docs.forEach(d -> sb.append("- ").append(d.getTitle()).append(" [").append(d.getStatus()).append("]\n"));
                yield new ToolResult("知识库共 " + docs.size() + " 个文档:\n" + sb,
                        Map.of("count", docs.size()), List.of(), List.of());
            }
            case "summarize_document" -> {
                Document doc = ownedDocument(userId, str(call.args(), "document_id", ""));
                List<DocumentChunk> chunks =
                        chunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());
                String text = String.join("\n", chunks.stream().map(DocumentChunk::getContent).toList());
                yield new ToolResult(summarize(text),
                        Map.of("title", doc.getTitle()), List.of(summarize(text)), List.of());
            }
            default -> throw ApiException.badRequest("未知工具: " + call.tool());
        };
    }

    private KnowledgeBase ownedKb(UUID kbId, UUID userId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
        if (!kb.getOwnerId().equals(userId)) {
            throw ApiException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    private Document ownedDocument(UUID userId, String documentIdStr) {
        UUID documentId;
        try {
            documentId = UUID.fromString(documentIdStr);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("document_id 不是合法 UUID");
        }
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在"));
        if (!kbRepository.findById(doc.getKnowledgeBaseId())
                .map(kb -> kb.getOwnerId().equals(userId)).orElse(false)) {
            throw ApiException.forbidden("无权访问该文档");
        }
        return doc;
    }

    private String summarize(String text) {
        List<String> sentences = new ArrayList<>();
        for (String s : text.split("(?<=[。!?\n])")) {
            if (s.strip().length() >= 10) {
                sentences.add(s.strip());
            }
            if (sentences.size() >= 6) {
                break;
            }
        }
        return String.join(" ", sentences);
    }

    private String snippet(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "…";
    }

    private String str(Map<String, Object> args, String key, String defaultValue) {
        Object v = args == null ? null : args.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
