package com.influora.repository;

import com.influora.domain.entity.Payout;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, String> {

    Optional<Payout> findByRazorpayPayoutId(String razorpayPayoutId);

    Optional<Payout> findByIdempotencyKey(String idempotencyKey);

    Optional<Payout> findByMilestoneId(String milestoneId);
}
