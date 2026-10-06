package com.aireshub.document;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 5 触发方式:上传事务提交后向 Kafka 发布 document.process 事件,
 * 由 Worker 消费执行摄入管线,HTTP 请求不再等待解析/Embedding。
 */
@Component
@ConditionalOnProperty(name = "app.pipeline.mode", havingValue = "kafka")
public class KafkaIngestionTrigger implements IngestionTrigger {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaIngestionTrigger(KafkaTemplate<String, String> kafkaTemplate,
                                 @org.springframework.beans.factory.annotation.Value("${app.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void trigger(UUID documentId) {
        kafkaTemplate.send(topic, documentId.toString(), documentId.toString());
    }
}
