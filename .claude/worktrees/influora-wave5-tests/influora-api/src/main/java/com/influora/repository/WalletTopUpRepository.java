package com.influora.repository;

import com.influora.domain.entity.WalletTopUp;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, String> {

    Optional<WalletTopUp> findByIdempotencyKey(String idempotencyKey);
}
