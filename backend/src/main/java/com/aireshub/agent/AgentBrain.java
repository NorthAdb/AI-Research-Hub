package com.aireshub.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent "大脑"(可替换接口):
 * - rule-based(默认,无 LLM Key):确定性决策;
 * - llm(OpenAI 兼容):把工具描述与历史给模型,解析其 JSON 决策。
 */
public interface AgentBrain {

    /** 决策:要么调用工具,要么给出最终回答。 */
    Decision decide(String question, List<StepView> history);

    /** 历史步骤视图(供大脑阅读)。 */
    record StepView(int stepNo, String tool, Map<String, Object> args, String resultSummary,
                    Map<String, Object> data) {
    }

    /** 工具调用意图。 */
    record ToolCall(String tool, Map<String, Object> args) {
    }

    record Decision(Optional<ToolCall> toolCall, Optional<String> finalAnswer) {
    }
}
