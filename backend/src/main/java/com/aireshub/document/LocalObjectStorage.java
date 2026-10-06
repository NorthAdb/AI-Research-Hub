package com.aireshub.document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地目录。storage_path 是相对 STORAGE_ROOT 的路径，已有文件不用搬迁。 */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    @Autowired
    public LocalObjectStorage(@Value("${app.storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public LocalObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public String write(UUID knowledgeBaseId, String filename, byte[] content) {
        try {
            Path dir = root.resolve(knowledgeBaseId.toString());
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + "_" + filename);
            Files.write(target, content);
            return root.relativize(target).toString();
        } catch (Exception ex) {
            throw new IllegalStateException("本地文件保存失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public byte[] read(String storagePath) {
        try {
            Path file = root.resolve(storagePath).normalize();
            if (!file.startsWith(root)) {
                throw new IllegalStateException("存储路径越出了根目录");
            }
            return Files.readAllBytes(file);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("本地文件读取失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Path file = root.resolve(storagePath).normalize();
            if (file.startsWith(root)) {
                Files.deleteIfExists(file);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("本地文件删除失败: " + ex.getMessage(), ex);
        }
    }
}
