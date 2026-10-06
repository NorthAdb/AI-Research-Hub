package com.aireshub.ai;

import java.util.List;

/**
 * LLM 服务(路线图 原则 5:可替换接口)。
 *
 * <p>默认实现 {@link ExtractiveLlmService} 无需 API Key(抽取式拼装);
 * 配置 app.llm.provider=openai 后切换为 OpenAI 兼容 Chat API(DeepSeek/OpenAI 等)。</p>
 */
public interface LlmService {

    LlmResult answer(String question, List<CitedContext> contexts);

    /** 带引用编号的上下文片段。 */
    record CitedContext(int n, String text) {
    }

    record LlmResult(String answer, String provider) {
    }
}
