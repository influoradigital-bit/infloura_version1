package com.influora.job;

import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.repository.AffiliateEarningRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [W1-7 / H15/H16] Extracted from {@link AffiliateSettlementJob#doSettleCreator} — this is the
 * actual mutating write, called by {@link AffiliateSettlementJob#settleOneCreator} FROM INSIDE the
 * {@link com.influora.service.IdempotencyService#executeOnce} supplier lambda it passes in. When
 * that lambda lived in {@code AffiliateSettlementJob} itself and called {@code
 * this.doSettleCreator(...)} directly, the call bypassed Spring's transactional proxy entirely (a
 * lambda captures the enclosing instance's raw {@code this}, exactly like an anonymous inner class
 * would) — {@code @Transactional} on that method was a silent no-op, so a failure partway through
 * marking a creator's earnings SETTLED (e.g. row 3 of 5 throws) would leave rows 1-2 durably
 * SETTLED with no rollback, double-counting risk on the next run's PENDING/FAILED sweep. Moving the
 * write to a genuinely separate {@code @Component} means {@link AffiliateSettlementJob} now calls
 * it through this bean's real Spring proxy, so {@code @Transactional} actually demarcates a
 * transaction — either every earning in the batch is marked SETTLED, or none are.
 */
@Component
public class AffiliateSettlementWriter {

    private final AffiliateEarningRepository affiliateEarningRepository;

    public AffiliateSettlementWriter(AffiliateEarningRepository affiliateEarningRepository) {
        this.affiliateEarningRepository = affiliateEarningRepository;
    }

    /**
     * Runs ONLY inside {@code executeOnce} (called from {@link
     * AffiliateSettlementJob#settleOneCreator}) — see class javadoc. Identical logic to the
     * pre-extraction {@code AffiliateSettlementJob#doSettleCreator}.
     */
    @Transactional
    public void doSettleCreator(List<AffiliateEarning> settleable, AffiliateSettlementBatch batch) {
        for (AffiliateEarning earning : settleable) {
            earning.markSettled(batch.getId());
            affiliateEarningRepository.save(earning);
        }
    }
}
