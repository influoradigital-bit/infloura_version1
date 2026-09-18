package com.influora.service.trendspark.ingest;

import com.influora.domain.entity.Trend;
import com.influora.repository.TrendRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — persists a run's screened, tagged {@link Trend} rows in
 * one short transaction, entered only after all outbound HTTP (fetch + classify) has completed.
 * Source: job-design.md step 14.
 *
 * <p>Deliberately a SEPARATE {@code @Component} from {@code TrendPullJob}, not a method on it —
 * {@code @Transactional} is silently inert on self-invocation (no proxy in the call path), a
 * documented live failure mode in this codebase (memory: "@Transactional silently inert").
 */
@Component
public class TrendIngestWriter {

    private final TrendRepository trendRepository;

    public TrendIngestWriter(TrendRepository trendRepository) {
        this.trendRepository = trendRepository;
    }

    @Transactional
    public int writeAll(List<Trend> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return trendRepository.saveAll(rows).size();
    }
}
