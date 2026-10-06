package com.aireshub.document;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Phase 2~4 的默认触发方式:进程内异步执行管线。 */
@Component
@ConditionalOnProperty(name = "app.pipeline.mode", havingValue = "inline", matchIfMissing = true)
public class InlineIngestionTrigger implements IngestionTrigger {

    private final IngestionPipeline pipeline;

    public InlineIngestionTrigger(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void trigger(UUID documentId) {
        pipeline.processAsync(documentId);
    }
}
