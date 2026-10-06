package com.aireshub.document;

import java.util.UUID;

/**
 * 原文文件放哪。默认是本地磁盘；STORAGE_PROVIDER=supabase 时改走 Supabase Storage。
 * 数据库里只存 write 返回的 storage_path。
 */
public interface ObjectStorage {

    String name();

    String write(UUID knowledgeBaseId, String filename, byte[] content);

    byte[] read(String storagePath);

    void delete(String storagePath);
}
