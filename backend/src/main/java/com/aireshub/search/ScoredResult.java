package com.aireshub.search;

import java.util.UUID;

/** 检索管道中的统一结果。score 语义随阶段变化(相似度/ts_rank/RRF/重排分)。 */
public record ScoredResult(
        UUID chunkId,
        UUID documentId,
        String documentTitle,
        String content,
        double score) {

    public ScoredResult copyWithScore(double newScore) {
        return new ScoredResult(chunkId, documentId, documentTitle, content, newScore);
    }
}
