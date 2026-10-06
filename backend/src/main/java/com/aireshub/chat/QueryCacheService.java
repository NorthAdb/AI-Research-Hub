package com.aireshub.chat;

import com.aireshub.search.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase 7(Roadmap)/查询缓存:query 归一化 → Redis GET → HIT 直接返回 / MISS 走 RAG 后 SET。
 * Redis 不可用时静默降级(缓存层故障不影响问答)。
 */
@Service
public class QueryCacheService {

    private static final Logger log = LoggerFactory.getLogger(QueryCacheService.class);
    private static final String PREFIX = "arh:chat:";

    /** 与 ChatService.ChatResponse 中可缓存的部分对应(不含 sessionId)。 */
    public record CachedAnswer(String answer, String provider, List<ChatService.Citation> citations,
                               SearchService.Trace trace, long latencyMs) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final io.micrometer.core.instrument.Counter hitCounter;
    private final io.micrometer.core.instrument.Counter missCounter;

    public QueryCacheService(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             io.micrometer.core.instrument.MeterRegistry meterRegistry,
                             @Value("${app.chat-cache.ttl-seconds:3600}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.hitCounter = io.micrometer.core.instrument.Counter
                .builder("arh_chat_cache_total").tag("result", "hit").register(meterRegistry);
        this.missCounter = io.micrometer.core.instrument.Counter
                .builder("arh_chat_cache_total").tag("result", "miss").register(meterRegistry);
    }

    public record Lookup(CachedAnswer answer, String key) {
    }

    /** 查缓存;任何 Redis 异常都降级为未命中。 */
    public Lookup get(UUID kbId, String question) {
        String key = keyOf(kbId, question);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                missCounter.increment();
                return new Lookup(null, key);
            }
            hitCounter.increment();
            return new Lookup(objectMapper.readValue(json, CachedAnswer.class), key);
        } catch (Exception ex) {
            log.warn("Redis 缓存读取失败,降级为未命中: {}", ex.getMessage());
            missCounter.increment();
            return new Lookup(null, key);
        }
    }

    /** 文档入库或删除后清掉该知识库的问答缓存,避免一小时内还返回旧答案。 */
    public void evictKnowledgeBase(UUID kbId) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(PREFIX + kbId + ":*").count(200).build();
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redis.scan(options)) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception ex) {
            log.warn("Redis 缓存失效失败(忽略): {}", ex.getMessage());
        }
    }

    public void put(String key, CachedAnswer answer) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(answer), ttl);
        } catch (Exception ex) {
            log.warn("Redis 缓存写入失败(忽略): {}", ex.getMessage());
        }
    }

    /** 归一化:trim + 小写 + 压缩空白,再以 kbId 为命名空间做 SHA-256。 */
    static String keyOf(UUID kbId, String question) {
        String normalized = question.strip().toLowerCase().replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return PREFIX + kbId + ":" + sb;
        } catch (Exception e) {
            return PREFIX + kbId + ":" + normalized.hashCode();
        }
    }
}
