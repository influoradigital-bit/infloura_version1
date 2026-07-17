package com.influora.repository;

import com.influora.domain.entity.WalletTransaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, String> {

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);

    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(String walletId);

    List<WalletTransaction> findByGroupId(String groupId);
}
