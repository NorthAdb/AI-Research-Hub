package com.aireshub.document;

import java.util.UUID;

/**
 * 摄入触发器(Phase 5 的换轨点):
 * - inline(默认):进程内 @Async 直接触发管线;
 * - kafka(Phase 5):发布 document.process 事件,由 Worker 消费。
 * 切换只动 app.pipeline.mode 配置。
 */
public interface IngestionTrigger {

    void trigger(UUID documentId);
}
