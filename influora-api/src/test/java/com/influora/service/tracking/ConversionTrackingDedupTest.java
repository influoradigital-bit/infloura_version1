package com.influora.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.IdempotencyKeyRecordRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyReservationOps;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * REGRESSION TEST ONLY (F-0523) -- no production code touched here.
 *
 * <p><b>The defect.</b> {@link ConversionTrackingService#recordConversion(String, String, String,
 * BigDecimal, String)}'s reservation key is {@code workspaceId + ":conv:" + utmCampaignId + ":" +
 * orderId} -- the caller-supplied {@code idempotencyKey} parameter plays NO part in the dedup
 * decision; it is only forwarded to {@link ConversionTrackingWriter#doRecordConversion} for the
 * audit-log row. Two genuinely distinct conversions that happen to share a workspace, UTM link and
 * order id therefore collapse into a single recorded conversion, and there is no way for a caller
 * to disambiguate them even by sending two different {@code idempotencyKey} values.
 *
 * <p>Unlike {@code ConversionTrackingServiceTest}'s existing replay-guard tests (which mock {@link
 * IdempotencyService} outright and only assert on the reservation-key STRING passed to {@code
 * executeOnce}), this test wires a REAL {@link IdempotencyService} + {@link
 * IdempotencyReservationOps} pair -- constructed directly, exactly as {@code
 * IdempotencyServiceTest} does -- backed by a small in-memory fake standing in for {@code
 * idempotency_keys}' real DB-level UNIQUE constraint (via {@link IdempotencyKeyRecordRepository
 * #saveAndFlush} throwing {@link DataIntegrityViolationException} on a second insert of the same
 * composite key). That lets this test exercise the ACTUAL dedup decision end-to-end -- including
 * the real {@code AlreadyCompletedException} branch -- rather than only asserting on what string
 * was passed to a mock.
 */
@ExtendWith(MockitoExtension.class)
class ConversionTrackingDedupTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String UTM_ID = "01HUTM1234567890ABCDE";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String ORDER_ID = "order-7001";

    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyKeyRecordRepository repository;

    private ConversionTrackingService service;

    /**
     * Stand-in for {@code idempotency_keys}' real DB-level UNIQUE constraint on the composite
     * reservation key -- see {@link IdempotencyReservationOps#tryReserve}'s own javadoc for why
     * production relies on {@code saveAndFlush} throwing {@link DataIntegrityViolationException}
     * on a duplicate insert rather than an application-level check.
     */
    private final Map<String, IdempotencyKeyRecord.Status> reservations = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(repository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            IdempotencyKeyRecord record = invocation.getArgument(0);
                            if (reservations.containsKey(record.getIdempotencyKey())) {
                                throw new DataIntegrityViolationException("duplicate idempotency key");
                            }
                            reservations.put(record.getIdempotencyKey(), IdempotencyKeyRecord.Status.IN_PROGRESS);
                            return record;
                        });
        lenient()
                .when(repository.findByIdempotencyKey(anyString()))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            IdempotencyKeyRecord.Status status = reservations.get(key);
                            if (status == null) {
                                return Optional.empty();
                            }
                            IdempotencyKeyRecord record =
                                    IdempotencyKeyRecord.builder().idempotencyKey(key).build();
                            if (status == IdempotencyKeyRecord.Status.COMPLETED) {
                                record.markCompleted(null);
                            } else if (status == IdempotencyKeyRecord.Status.FAILED) {
                                record.markFailed();
                            }
                            return Optional.of(record);
                        });
        lenient()
                .when(
                        repository.markCompleted(
                                anyString(), eq(IdempotencyKeyRecord.Status.COMPLETED), any(), any()))
                .thenAnswer(
                        invocation -> {
                            reservations.put(invocation.getArgument(0), IdempotencyKeyRecord.Status.COMPLETED);
                            return 1;
                        });

        // Constructed directly (not through Spring), exactly as IdempotencyServiceTest does --
        // this test exercises IdempotencyService's real branching logic against the fake
        // repository above, not IdempotencyReservationOps's transactional-boundary behavior
        // (covered separately by IdempotencyServicePersistenceTest).
        IdempotencyReservationOps reservationOps = new IdempotencyReservationOps(repository);
        IdempotencyService idempotencyService = new IdempotencyService(repository, reservationOps);
        // Real ConversionTrackingWriter (not mocked), same pattern as ConversionTrackingServiceTest,
        // so both calls below exercise the actual doRecordConversion mutation, not a stub.
        ConversionTrackingWriter writer = new ConversionTrackingWriter(utmCampaignRepository, auditLogService);
        service = new ConversionTrackingService(idempotencyService, writer);
    }

    @Test
    @DisplayName(
            "F-0523: two conversions sharing workspace+UTM+orderId but DIFFERENT caller idempotency"
                    + " keys must both be recorded")
    void testDifferentCallerKeysBothRecorded() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordConversion(WORKSPACE_ID, UTM_ID, ORDER_ID, BigDecimal.valueOf(150), "caller-key-A");
        service.recordConversion(WORKSPACE_ID, UTM_ID, ORDER_ID, BigDecimal.valueOf(150), "caller-key-B");

        assertEquals(
                2,
                utm.getConversionCount(),
                "two conversions with DIFFERENT caller idempotency keys collapsed into one -- the"
                        + " reservation key (workspaceId+utmCampaignId+orderId) never incorporates the"
                        + " caller-supplied idempotencyKey, so the second, genuinely distinct delivery was"
                        + " wrongly treated as a duplicate of the first");
    }

    @Test
    @DisplayName(
            "F-0523: two conversions with the SAME caller idempotency key (same workspace+UTM+orderId)"
                    + " are recorded exactly once")
    void testSameCallerKeyRecordedOnce() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordConversion(WORKSPACE_ID, UTM_ID, ORDER_ID, BigDecimal.valueOf(150), "same-caller-key");
        service.recordConversion(WORKSPACE_ID, UTM_ID, ORDER_ID, BigDecimal.valueOf(150), "same-caller-key");

        assertEquals(1, utm.getConversionCount());
    }

    private static UtmCampaign utm() {
        return UtmCampaign.builder()
                .id(UTM_ID)
                .campaignId(CAMPAIGN_ID)
                .collaborationId("01HCOLLAB1234567890AB")
                .creatorProfileId(CREATOR_PROFILE_ID)
                .baseUrl("https://example.com")
                .build();
    }
}
