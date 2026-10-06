package com.aireshub.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 取当前登录用户;未登录时抛 401(正常不会发生,过滤器已保证)。 */
    public static AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return user;
        }
        throw ApiException.unauthorized("未登录或 token 无效");
    }
}
