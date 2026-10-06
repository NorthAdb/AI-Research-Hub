package com.aireshub.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Modifying
    @Query(value = """
            insert into users (id, email, display_name, password_hash, created_at)
            values (:id, :email, :displayName, null, now())
            """, nativeQuery = true)
    void insertExternal(UUID id, String email, String displayName);
}
