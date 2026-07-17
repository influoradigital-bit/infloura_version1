package com.influora.repository;

import com.influora.domain.entity.InvoiceNumberSequence;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceNumberSequenceRepository extends JpaRepository<InvoiceNumberSequence, String> {

    /**
     * Row lock for the increment-and-persist step in {@code InvoiceNumberService.generateNext} —
     * mandatory: two concurrent invoice-issuing transactions must never read the same {@code
     * next_seq} and both mint the same statutory number.
     *
     * <p>{@code creatorInvoiceCode} is matched with {@code IS NULL}-safe equality via the
     * null-handling overloads below rather than here, since JPQL {@code =} does not match NULL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT s FROM InvoiceNumberSequence s WHERE s.seriesType = :seriesType"
                    + " AND s.fiscalYear = :fiscalYear AND s.creatorInvoiceCode IS NULL")
    Optional<InvoiceNumberSequence> findForUpdateGlobal(
            @Param("seriesType") InvoiceNumberSeriesType seriesType,
            @Param("fiscalYear") String fiscalYear);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT s FROM InvoiceNumberSequence s WHERE s.seriesType = :seriesType"
                    + " AND s.fiscalYear = :fiscalYear AND s.creatorInvoiceCode = :creatorInvoiceCode")
    Optional<InvoiceNumberSequence> findForUpdatePerCreator(
            @Param("seriesType") InvoiceNumberSeriesType seriesType,
            @Param("fiscalYear") String fiscalYear,
            @Param("creatorInvoiceCode") String creatorInvoiceCode);
}
