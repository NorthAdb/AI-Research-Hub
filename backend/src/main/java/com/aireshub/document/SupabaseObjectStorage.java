package com.aireshub.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Supabase Storage REST。用 service role，不把桶暴露成公开下载。
 * 对象键是 {知识库}/{随机id}_{文件名}，和本地目录的分层一致。
 */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "supabase")
public class SupabaseObjectStorage implements ObjectStorage {

    private final RestClient client;
    private final String bucket;

    @Autowired
    public SupabaseObjectStorage(
            @Value("${app.storage.supabase-url:}") String supabaseUrl,
            @Value("${app.storage.supabase-service-role-key:}") String serviceRoleKey,
            @Value("${app.storage.bucket:documents}") String bucket) {
        if (supabaseUrl == null || supabaseUrl.isBlank() || serviceRoleKey == null || serviceRoleKey.isBlank()) {
            throw new IllegalStateException(
                    "STORAGE_PROVIDER=supabase 但 SUPABASE_URL 或 SUPABASE_SERVICE_ROLE_KEY 为空。不接云端时请保持 STORAGE_PROVIDER=local。");
        }
        this.bucket = bucket;
        String base = supabaseUrl.endsWith("/") ? supabaseUrl.substring(0, supabaseUrl.length() - 1) : supabaseUrl;
        this.client = RestClient.builder()
                .baseUrl(base + "/storage/v1")
                .defaultHeader("Authorization", "Bearer " + serviceRoleKey)
                .build();
    }

    public SupabaseObjectStorage(RestClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public String name() {
        return "supabase";
    }

    @Override
    public String write(UUID knowledgeBaseId, String filename, byte[] content) {
        String key = knowledgeBaseId + "/" + UUID.randomUUID() + "_" + filename;
        client.post()
                .uri("/object/" + bucket + "/" + key)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("x-upsert", "true")
                .body(content)
                .retrieve()
                .toBodilessEntity();
        return key;
    }

    @Override
    public byte[] read(String storagePath) {
        byte[] body = client.get()
                .uri("/object/" + bucket + "/" + storagePath)
                .retrieve()
                .body(byte[].class);
        if (body == null) {
            throw new IllegalStateException("Supabase Storage 没有返回文件内容");
        }
        return body;
    }

    @Override
    public void delete(String storagePath) {
        client.method(HttpMethod.DELETE)
                .uri("/object/" + bucket)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prefixes", List.of(storagePath)))
                .retrieve()
                .toBodilessEntity();
    }
}
