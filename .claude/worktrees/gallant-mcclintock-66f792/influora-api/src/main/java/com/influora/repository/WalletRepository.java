package com.influora.repository;

import com.influora.domain.entity.Wallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.ownerId = :ownerId")
    Optional<Wallet> findByOwnerIdForUpdate(@Param("ownerId") String ownerId);

    Optional<Wallet> findByOwnerId(String ownerId);
}
