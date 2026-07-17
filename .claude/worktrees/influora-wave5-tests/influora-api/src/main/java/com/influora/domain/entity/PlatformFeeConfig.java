package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Singleton platform-fee configuration ({@code platform_fee_config}, V41__platform_fee_config.sql
 * — note: originally authored as "V40" by a concurrent session that also independently claimed
 * V40 for {@code reviews}; renamed to V41 to resolve the resulting Flyway version collision, see
 * that migration's header). Stores the CREATOR-side take rate in basis points (e.g. {@code 1500 =
 * 15.00%}) — {@link #defaultFeeBps} — and is read/written by {@code PlatformFeeService} to deduct
 * the creator fee at escrow release.
 *
 * <p><b>{@link #brandFeeBps}/{@link #razorpayAbsorbedByPlatform}/{@link #approvedBy}/{@link
 * #effectiveAt} (V42__platform_fee_config_brand_fee_razorpay.sql, Vikram, CEO directive
 * wiki/decisions/admin-pending-tasks-directive.md item #5):</b> the admin-facing brand-side fee
 * and Razorpay-cost-absorption flag the CEO's ruling requires, added onto this existing singleton
 * row rather than a new table — see that migration's header for why. {@code PlatformFeeAdminController}
 * (admin CRUD/read surface only — brand/creator escrow-charging integration for the brand leg is
 * separate, not-yet-built scope) is the only writer of these four fields, via {@link
 * #applyAdminFeeUpdate}; {@code PlatformFeeService}/the creator escrow-release path never touches
 * them. Admin-editable without redeploy — the default seed values live in the Flyway migrations,
 * not in Java constants.
 */
@Entity
@Table(name = "platform_fee_config")
public class PlatformFeeConfig {

  public static final String SINGLETON_ID = "default";

  @Id
  @Column(length = 26)
  private String id;

  @Column(name = "default_fee_bps", nullable = false)
  private int defaultFeeBps;

  /** Brand-side fee in basis points (e.g. {@code 1000 = 10.00%}) — V42, admin-editable only. */
  @Column(name = "brand_fee_bps", nullable = false)
  private int brandFeeBps;

  @Column(name = "min_fee_bps", nullable = false)
  private int minFeeBps;

  @Column(name = "max_fee_bps", nullable = false)
  private int maxFeeBps;

  @Column(name = "allow_high_fee", nullable = false)
  private boolean allowHighFee;

  /** Option A per the CEO ruling: {@code true} = platform absorbs Razorpay processing costs. */
  @Column(name = "razorpay_absorbed_by_platform", nullable = false)
  private boolean razorpayAbsorbedByPlatform;

  /** Free-text attribution/justification for the CURRENT values above — see V42 header for why
   * this is not an {@code admin_users} FK (the seeded row's approval happened via written
   * directive, not an admin-panel action). Dual-purpose by design: the seed value reads as a
   * literal approver name ("Swapnil Maruti (CEO)"); every subsequent value is whatever the acting
   * admin typed into {@code FeeControlPanel.tsx}'s single "reason" box (e.g. "Rate cut approved by
   * Swapnil per Slack thread") — {@code PlatformFeeAdminService#update} passes the same {@code
   * PlatformFeeUpdateRequest.reason} string here AND to the audit log's {@code reason}, since the
   * frontend contract has one text field, not two. */
  @Column(name = "approved_by")
  private String approvedBy;

  /** When this fee structure takes effect — not retroactive, matches the versioning intent in
   * wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md §1A.3 even though this table itself is a
   * singleton, not a per-version row (see V42 header). */
  @Column(name = "effective_at")
  private Instant effectiveAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 26)
  private String updatedBy;

  /**
   * Optimistic-locking column (V44__platform_fee_config_version.sql, Vikram, per Kabir's security
   * review of {@code PlatformFeeAdminController}): without this, two concurrent SUPER_ADMIN PUT
   * requests against this singleton row (or a client-side retry racing the original request) can
   * silently lost-update each other's fee change — last write wins with no error and no audit
   * trail of the collision, on the platform's highest-stakes revenue config. Hibernate increments
   * this on every {@code UPDATE} and checks it in the {@code WHERE} clause; a stale read now throws
   * {@code ObjectOptimisticLockingFailureException}, which {@code PlatformFeeAdminController}
   * translates to an HTTP 409 instead of a silent overwrite.
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected PlatformFeeConfig() {}

  public String getId() {
    return id;
  }

  public int getDefaultFeeBps() {
    return defaultFeeBps;
  }

  public int getBrandFeeBps() {
    return brandFeeBps;
  }

  public int getMinFeeBps() {
    return minFeeBps;
  }

  public int getMaxFeeBps() {
    return maxFeeBps;
  }

  public boolean isAllowHighFee() {
    return allowHighFee;
  }

  public boolean isRazorpayAbsorbedByPlatform() {
    return razorpayAbsorbedByPlatform;
  }

  public String getApprovedBy() {
    return approvedBy;
  }

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public long getVersion() {
    return version;
  }

  /**
   * The only mutator on this entity — called exclusively by {@code PlatformFeeAdminService} from
   * {@code PlatformFeeAdminController}'s PUT endpoint (SUPER_ADMIN-gated). Deliberately does NOT
   * touch {@link #minFeeBps}/{@link #maxFeeBps}/{@link #allowHighFee} — those range-guard columns
   * are shared range bounds the admin service validates {@code brandFeeBps}/{@code creatorFeeBps}
   * against before calling this, not fields this update itself changes.
   */
  public void applyAdminFeeUpdate(
      int brandFeeBps,
      int creatorFeeBps,
      boolean razorpayAbsorbedByPlatform,
      String approvedBy,
      Instant effectiveAt,
      String updatedByAdminId) {
    this.brandFeeBps = brandFeeBps;
    this.defaultFeeBps = creatorFeeBps;
    this.razorpayAbsorbedByPlatform = razorpayAbsorbedByPlatform;
    this.approvedBy = approvedBy;
    this.effectiveAt = effectiveAt;
    this.updatedAt = Instant.now();
    this.updatedBy = updatedByAdminId;
  }
}
