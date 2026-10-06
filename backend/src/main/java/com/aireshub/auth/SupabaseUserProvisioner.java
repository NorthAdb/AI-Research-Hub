package com.aireshub.auth;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supabase 的用户 id 就是 JWT 的 sub。第一次带着合法令牌进来时，
 * 在本地 users 表补一行资料，密码留空，知识库仍然引用这个 id。
 */
@Service
public class SupabaseUserProvisioner {

    private final UserRepository userRepository;

    public SupabaseUserProvisioner(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserEntity ensure(UUID id, String email) {
        return userRepository.findById(id).orElseGet(() -> {
            if (userRepository.existsByEmail(email)) {
                throw new IllegalStateException("该邮箱已属于另一个本地账号");
            }
            String name = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            userRepository.insertExternal(id, email, name);
            return userRepository.findById(id).orElseThrow();
        });
    }
}
