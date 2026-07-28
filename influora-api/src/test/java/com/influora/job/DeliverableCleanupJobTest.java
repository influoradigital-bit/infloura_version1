package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DPF-8 lifecycle cleanup guard tests — locks in Kabir's red-team findings
 * (wiki/errors/DPF-8-kabir-redteam.md) so they cannot silently regress:
 *
 * <ul>
 *   <li>H-DPF8-1 — FROZEN/PENDING escrow (not just FUNDED) must block deletion
 *   <li>H-DPF8-2 — the real {@code url}/{@code thumbnailUrl} key fields must be used, and {@code
 *       filesJson} must only clear once the R2 delete actually succeeds
 *   <li>H-DPF8-3 — cleanup must never move {@code status} backward
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DeliverableCleanupJobTest {

    private static final String COLLAB_ID = "01HCOLLAB0000000000000001";

    private static final String FILES_JSON =
            "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"a.mp4\","
                    + "\"url\":\"deliverables/01HDLV000000000000001/v1/abc-a.mp4\","
                    + "\"thumbnailUrl\":\"deliverables/01HDLV000000000000001/v1/thumb-xyz\","
                    + "\"fileSize\":100,\"durationSeconds\":null,\"md5Hash\":\"h\"}]";

    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private R2StorageService r2StorageService;

    private DeliverableCleanupJob job;

    @BeforeEach
    void setUp() {
        job =
                new DeliverableCleanupJob(
                        deliverableRepository, disputeRepository, escrowHoldRepository, r2StorageService);
        ReflectionTestUtils.setField(job, "dryRun", false);
        when(r2StorageService.isAvailable()).thenReturn(true);
    }

    private static Deliverable abandonedDraft(String filesJson) {
        return Deliverable.builder()
                .id("01HDLV000000000000001")
                .collaborationId(COLLAB_ID)
                .status(DeliverableStatus.REJECTED)
                .filesJson(filesJson)
                .build();
    }

    @Test
    @DisplayName("H-DPF8-1: FROZEN escrow blocks deletion (not just FUNDED)")
    void frozenEscrowBlocksDeletion() {
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(abandonedDraft(FILES_JSON)));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        // Simulates a FROZEN (not FUNDED) hold — real repo query would find it via the full set.
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(true);

        job.cleanupAbandonedDrafts();

        verify(r2StorageService, never()).deleteObject(anyString());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("H-DPF8-1: guard queries the full unreleased set {FUNDED, FROZEN, PENDING}, never just FUNDED")
    void escrowGuardChecksFullUnreleasedSet() {
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(abandonedDraft(FILES_JSON)));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);

        job.cleanupAbandonedDrafts();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<EscrowStatus>> statusesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(escrowHoldRepository)
                .existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), statusesCaptor.capture());
        Set<EscrowStatus> statuses = statusesCaptor.getValue();

        assertTrue(statuses.contains(EscrowStatus.FUNDED));
        assertTrue(statuses.contains(EscrowStatus.FROZEN), "FROZEN must be in the guarded set");
        assertTrue(statuses.contains(EscrowStatus.PENDING), "PENDING must be in the guarded set");
        assertFalse(statuses.contains(EscrowStatus.RELEASED));
        assertFalse(statuses.contains(EscrowStatus.REFUNDED));
    }

    @Test
    @DisplayName("H-DPF8-2: deletes the real url/thumbnailUrl key fields, not a nonexistent objectKey field")
    void deletesRealKeyFields() {
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(abandonedDraft(FILES_JSON)));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);

        job.cleanupAbandonedDrafts();

        verify(r2StorageService).deleteObject("deliverables/01HDLV000000000000001/v1/abc-a.mp4");
        verify(r2StorageService).deleteObject("deliverables/01HDLV000000000000001/v1/thumb-xyz");
    }

    @Test
    @DisplayName("H-DPF8-2: filesJson is cleared only after the R2 deletes succeed")
    void filesJsonClearedOnlyAfterSuccessfulDelete() {
        Deliverable d = abandonedDraft(FILES_JSON);
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(d));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);

        job.cleanupAbandonedDrafts();

        assertNull(d.getFilesJson());
        verify(deliverableRepository).save(d);
    }

    @Test
    @DisplayName("H-DPF8-2: a failed R2 delete leaves filesJson intact — no orphaned DB->key mapping")
    void filesJsonNotClearedOnFailedDelete() {
        Deliverable d = abandonedDraft(FILES_JSON);
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(d));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);
        doThrow(new RuntimeException("R2 down")).when(r2StorageService).deleteObject(anyString());

        job.cleanupAbandonedDrafts();

        assertEquals(FILES_JSON, d.getFilesJson());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("H-DPF8-3: cleanup never changes status — a REJECTED deliverable stays REJECTED")
    void cleanupNeverChangesStatus() {
        Deliverable d = abandonedDraft(FILES_JSON); // built with status REJECTED
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(d));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);

        job.cleanupAbandonedDrafts();

        assertEquals(DeliverableStatus.REJECTED, d.getStatus());
    }

    @Test
    @DisplayName("dry-run mode never deletes from R2 and never mutates the DB row")
    void dryRunNeverMutates() {
        ReflectionTestUtils.setField(job, "dryRun", true);
        Deliverable d = abandonedDraft(FILES_JSON);
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(d));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);

        job.cleanupAbandonedDrafts();

        verify(r2StorageService, never()).deleteObject(anyString());
        verify(deliverableRepository, never()).save(any());
        assertEquals(FILES_JSON, d.getFilesJson());
    }

    @Test
    @DisplayName("active dispute blocks deletion and short-circuits before the escrow check")
    void activeDisputeBlocksDeletion() {
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(abandonedDraft(FILES_JSON)));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(true);

        job.cleanupAbandonedDrafts();

        verify(r2StorageService, never()).deleteObject(anyString());
        verify(escrowHoldRepository, never()).existsByCollaborationIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("R2 unavailable: job skips entirely, no repository calls")
    void r2UnavailableSkipsJob() {
        when(r2StorageService.isAvailable()).thenReturn(false);

        job.cleanupAbandonedDrafts();

        verify(deliverableRepository, never())
                .findByStatusInAndUpdatedAtBefore(any(), any(Instant.class));
    }

    @Test
    @DisplayName(
            "[SEC: CR-35 follow-on] the escrow guard uses the MILESTONE-AWARE lookup, never the"
                    + " column-only one that is blind to ordinary brand-funded holds")
    void escrowGuardUsesMilestoneAwareLookup() {
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(abandonedDraft(FILES_JSON)));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLAB_ID), any()))
                .thenReturn(false);
        when(escrowHoldRepository.existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any()))
                .thenReturn(false);

        job.cleanupAbandonedDrafts();

        verify(escrowHoldRepository).existsForCollaborationIncludingMilestoneLink(eq(COLLAB_ID), any());
        // The tripwire. `existsByCollaborationIdAndStatusIn` matches ONLY the direct
        // `collaboration_id` column, which is NULL on every hold the ordinary brand escrow flow
        // creates — so it answered "no unreleased escrow" for exactly the collaborations this guard
        // protects, and this job deleted creator media against funded/frozen/in-flight holds.
        // Switching back to it must fail here, not silently start deleting again at 02:00.
        verify(escrowHoldRepository, never()).existsByCollaborationIdAndStatusIn(anyString(), any());
    }

    @Test
    @DisplayName(
            "[SEC: CR-35 follow-on] refuses to delete when the collaboration is unknown — fails"
                    + " CLOSED rather than treating 'cannot tell' as 'nothing held'")
    void refusesToDeleteWhenCollaborationIdIsMissing() {
        Deliverable orphan =
                Deliverable.builder()
                        .id("01HDLV000000000000009")
                        .collaborationId(null)
                        .status(DeliverableStatus.REJECTED)
                        .filesJson(FILES_JSON)
                        .build();
        when(deliverableRepository.findByStatusInAndUpdatedAtBefore(any(), any(Instant.class)))
                .thenReturn(List.of(orphan));

        job.cleanupAbandonedDrafts();

        // Nothing deleted, and neither guard was even consulted — there is no id to consult them
        // with. The failure mode this rules out is the one the original bug was made of: an
        // unanswerable question being scored as a reassuring answer.
        verify(r2StorageService, never()).deleteObject(anyString());
        verify(escrowHoldRepository, never()).existsForCollaborationIncludingMilestoneLink(any(), any());
        verify(disputeRepository, never()).existsByCollaborationIdAndStatusIn(any(), any());
    }
}
