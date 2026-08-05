package com.influora.repository;

import com.influora.domain.entity.Notification;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for in-app notifications (Domain B). All queries are tenant-scoped via
 * {@code userId} — no unscoped {@code findAll} is exposed.
 */
public interface NotificationRepository extends JpaRepository<Notification, String> {

    /** Paginated, unread-first, most-recent-first list for a user. */
    @Query(
            "SELECT n FROM Notification n WHERE n.userId = :userId "
                    + "ORDER BY n.isRead ASC, n.createdAt DESC")
    Page<Notification> findByUserIdOrdered(@Param("userId") String userId, Pageable pageable);

    /** Count unread notifications for badge display. */
    long countByUserIdAndIsReadFalse(String userId);

    /** Find by id and userId for tenant-safe updates. */
    Notification findByIdAndUserId(String id, String userId);

    /** Recent notifications for a user (limit by pageable). */
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * Bulk-mark every unread notification for a user as read — powers {@code POST
     * /notifications/read-all}. A single UPDATE instead of one save per row (the frontend
     * previously fired one request per unread notification). Tenant-scoped to {@code userId}.
     * Returns the number of rows flipped.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
    int markAllReadForUser(@Param("userId") String userId);
}
