package com.aireshub.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 抽取式 LLM 兜底(无 API Key 时的默认实现):
 * 对问题与上下文做词面重合度打分,取最相关的几句原文拼成回答并标注 [n] 引用。
 * 诚实标注:这是检索增强的抽取式回答,不是生成式 LLM。
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "extractive", matchIfMissing = true)
public class ExtractiveLlmService implements LlmService {

    private static final int MAX_SENTENCES = 3;

    @Override
    public LlmResult answer(String question, List<CitedContext> contexts) {
        Set<String> queryTokens = new HashSet<>(TextTokenizer.tokenize(question));
        if (queryTokens.isEmpty() || contexts.isEmpty()) {
            return new LlmResult("知识库中未找到与问题相关的内容,请换个问法或先上传相关文档。", "extractive");
        }

        record Scored(String sentence, int n, double score, int order) {
        }
        List<Scored> all = new ArrayList<>();
        int order = 0;
        for (CitedContext ctx : contexts) {
            for (String sentence : splitSentences(ctx.text())) {
                if (sentence.length() < 6) {
                    continue;
                }
                Set<String> sentenceTokens = new HashSet<>(TextTokenizer.tokenize(sentence));
                long overlap = sentenceTokens.stream().filter(queryTokens::contains).count();
                if (overlap == 0) {
                    order++;
                    continue;
                }
                double score = overlap / Math.sqrt((double) Math.max(1, queryTokens.size())
                        * Math.max(1, sentenceTokens.size()));
                all.add(new Scored(sentence, ctx.n(), score, order++));
            }
        }

        if (all.isEmpty()) {
            return new LlmResult("知识库中未找到与问题直接相关的句子,请尝试更具体的提问。", "extractive");
        }
        all.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> picked = all.subList(0, Math.min(MAX_SENTENCES, all.size()));
        picked.sort(Comparator.comparingInt(Scored::order));
        StringBuilder answer = new StringBuilder();
        for (Scored s : picked) {
            if (answer.length() > 0) {
                answer.append(' ');
            }
            answer.append(s.sentence()).append(" [").append(s.n()).append(']');
        }
        return new LlmResult(answer.toString(), "extractive");
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '!' || c == '?' || c == '\n' || c == ';' || c == '.') {
                if (current.length() > 0) {
                    sentences.add(current.toString().trim());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString().trim());
        }
        return sentences;
    }
}
