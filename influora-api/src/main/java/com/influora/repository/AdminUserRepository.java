package com.influora.repository;

import com.influora.domain.entity.AdminUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, String> {

    Optional<AdminUser> findByEmailIgnoreCase(String email);
}
