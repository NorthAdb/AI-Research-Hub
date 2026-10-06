package com.aireshub.common;

import java.util.UUID;

/**
 * 认证通过后放入 SecurityContext 的当前用户。
 * 对应路线图 Phase 2:JWT 校验 → 识别 user_id → 用户级数据隔离。
 */
public record AuthUser(UUID id, String email, String displayName) {
}
