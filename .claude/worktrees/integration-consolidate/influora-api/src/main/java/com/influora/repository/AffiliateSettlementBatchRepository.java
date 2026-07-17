package com.influora.repository;

import com.influora.domain.entity.AffiliateSettlementBatch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code affiliate_settlement_batches} (V27) -- Wave D task D4. */
public interface AffiliateSettlementBatchRepository extends JpaRepository<AffiliateSettlementBatch, String> {

    /** All batches attempted for a period -- a FAILED batch does not block creating a fresh one for the same period (see entity javadoc). */
    List<AffiliateSettlementBatch> findByPeriodYearMonth(String periodYearMonth);
}
