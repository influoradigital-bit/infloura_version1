package com.influora.repository;

import com.influora.domain.entity.IdempotencyKeyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRecordRepository extends JpaRepository<IdempotencyKeyRecord, String> {

    Optional<IdempotencyKeyRecord> findByIdempotencyKey(String idempotencyKey);
}
