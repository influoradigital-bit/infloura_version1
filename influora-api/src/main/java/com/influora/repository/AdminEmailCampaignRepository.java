package com.influora.repository;

import com.influora.domain.entity.AdminEmailCampaign;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@code admin_email_campaigns} (T-ADMINMAIL-0903). */
public interface AdminEmailCampaignRepository extends JpaRepository<AdminEmailCampaign, String> {

    /**
     * The most recent CONFIRMED send, across every admin -- {@code AdminCustomEmailService} uses
     * this to enforce the persisted, cross-instance rate limit (control #1:
     * {@code influora.admin-custom-email.min-interval-minutes}). Deliberately not scoped to one
     * {@code admin_user_id}: the spec requires the throttle to apply "across all admins," not
     * per-admin.
     */
    Optional<AdminEmailCampaign> findTopByOrderByCreatedAtDesc();
}
