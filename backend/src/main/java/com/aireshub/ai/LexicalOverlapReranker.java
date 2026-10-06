package com.aireshub.ai;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.aireshub.search.ScoredResult;

/**
 * 词面重合度 Reranker(演示级 Cross-Encoder 替代):
 * 对融合后的候选重新按 query-token 覆盖度打分。
 * 切换真正的 Cross-Encoder / API 时实现本接口即可,业务代码零改动。
 */
@Component
@ConditionalOnProperty(name = "app.reranker.provider", havingValue = "lexical", matchIfMissing = true)
public class LexicalOverlapReranker implements RerankerService {

    @Override
    public List<ScoredResult> rerank(String query, List<ScoredResult> candidates, int topK) {
        Set<String> queryTokens = new HashSet<>(TextTokenizer.tokenize(query));
        List<ScoredResult> reranked = candidates.stream()
                .map(r -> r.copyWithScore(overlapScore(queryTokens, r.content())))
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .toList();
        if (topK >= reranked.size()) {
            return reranked;
        }
        return reranked.subList(0, topK);
    }

    private double overlapScore(Set<String> queryTokens, String content) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> tokens = new HashSet<>(TextTokenizer.tokenize(content));
        if (tokens.isEmpty()) {
            return 0;
        }
        long overlap = tokens.stream().filter(queryTokens::contains).count();
        return 2.0 * overlap / (queryTokens.size() + tokens.size());
    }
}
