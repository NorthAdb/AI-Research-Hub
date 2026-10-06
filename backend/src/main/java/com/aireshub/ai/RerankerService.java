package com.aireshub.ai;

import java.util.List;
import com.aireshub.search.ScoredResult;

/**
 * 重排服务(路线图 原则 5:可替换接口)。
 * 输入融合后的候选,输出按相关性重排的前 topK。
 */
public interface RerankerService {

    List<ScoredResult> rerank(String query, List<ScoredResult> candidates, int topK);
}
