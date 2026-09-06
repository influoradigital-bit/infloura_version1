package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.common.SlugUtils;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.entity.PasswordResetToken;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalEnquiryType;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.FestivalEnquiryRepository;
import com.influora.repository.PasswordResetTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import com.influora.service.notification.event.PasswordResetEvent;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.ExistingSponsorAccountResponse;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.LinkExistingSponsorResponse;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.ProvisionFestivalSponsorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a {@code WON}/{@code BRAND} {@code festival_enquiries} row into a real sponsor account
 * (T-FESTIVALBOX-0905 phase 2): a {@link User}, a {@link Workspace}, a {@link WorkspaceMember}
 * ownership row, a {@link Wallet}, and a {@code DRAFT} {@link Campaign} — the four things someone
 * currently creates by hand once a lead is won. Backs {@code POST
 * /admin/festival-enquiries/{id}/provision}.
 *
 * <p><b>Admin auth lives here</b>, not in the controller — this codebase has no
 * {@code @PreAuthorize} anywhere (see {@link AdminContextService}'s class javadoc), so a
 * controller with no service-layer gate is an open route.
 *
 * <p><b>Insert order matters and copies {@code AuthService#brandRegister} exactly</b> (see that
 * method's class javadoc, ~line 120): {@code workspace_members} and {@code wallets} carry hard
 * FK-id columns on {@code users.id}/{@code workspaces.id} — plain columns, not JPA associations —
 * so Hibernate flushes in call order, not dependency order. The user row (the one with the
 * {@code UNIQUE(email)} constraint) is {@code saveAndFlush}'d first and specifically wrapped in a
 * try/catch for {@link DataIntegrityViolationException}, for the same TOCTOU reason
 * {@code AuthService#brandRegister} does it: an unflushed {@code save()} would defer the
 * constraint check to this method's surrounding {@code @Transactional} commit, which happens
 * after the catch block has already exited, letting a raced duplicate email escape as an
 * uncaught 500 instead of the intended 409.
 *
 * <p><b>Idempotency (F-0650):</b> {@link FestivalEnquiryRepository#findByIdForUpdate} takes a
 * {@code PESSIMISTIC_WRITE} row lock on the enquiry, held by this method's own
 * {@code @Transactional} until commit/rollback — there is no separate lock to release early, which
 * is exactly the shape that made the first {@code F-0650} fix (a MySQL named lock released inside
 * the transactional callback, before commit) still broken for entities with an assigned
 * {@code @Id}. {@code provisionedWorkspaceId == null} is checked immediately after acquiring that
 * lock; a second concurrent call simply blocks on the SELECT until the first transaction commits,
 * then sees the field already set and returns {@code 409 ALREADY_PROVISIONED}.
 *
 * <p><b>An existing account is refused, never silently attached</b> ({@code 409
 * EMAIL_ALREADY_REGISTERED}): grafting a brand-new workspace onto whatever account already holds
 * that email is an account-hijack shape regardless of who asked for it. An admin who actually
 * wants that has to do it through a different, deliberate action — not this one.
 *
 * <p><b>No password an admin or attacker could ever type in</b>: the user's password hash comes
 * from a fresh {@link SecureRandom} draw run through the injected {@link PasswordEncoder}, never
 * stored or logged in raw form. The brand's actual path in is the emailed password-set link,
 * built on the existing {@link PasswordResetToken} machinery ({@code AuthService}
 * ~line 677 shows the shape this copies: random raw token, only its SHA-256 hash persisted) but
 * with a 7-day expiry instead of forgot-password's 1 hour — this is an onboarding handoff the
 * brand may not open same-day, not a self-service reset.
 *
 * <p><b>Phase 8 — the returning-sponsor path ({@link #linkExisting}):</b> {@link #provision}'s
 * {@code EMAIL_ALREADY_REGISTERED} refusal is correct for a stranger reusing someone else's
 * address, but it also blocks the legitimate case of a brand that sponsored a previous edition
 * coming back: they already have a {@link User}, a {@link Workspace}, and a login. {@link
 * #linkExisting} is a SECOND, explicitly-chosen admin action for exactly that case — it does not
 * loosen {@link #provision}'s refusal in any way. It creates only a new {@code DRAFT} {@link
 * Campaign} on the admin-supplied, already-existing workspace: no new {@link User}, no new {@link
 * Workspace}, no {@link Wallet}, no password-set email — an established account needs none of
 * that, and emailing a password-reset-shaped link to it unprompted would read as phishing. {@link
 * #findExistingAccountForEmail} is the read-only companion that makes the {@code
 * EMAIL_ALREADY_REGISTERED} refusal actionable: it resolves the enquiry's email to the existing
 * workspace id/name so the admin console can offer "link to the existing workspace instead" and
 * feed that id into {@link #linkExisting}.
 *
 * <p><b>Two things that are easy to get wrong about that pair</b> (both were, and are fixed):
 *
 * <ul>
 *   <li>[Kabir H-2] The read is a SUGGESTION; the write does its own verification. {@link
 *       #linkExisting} independently requires the enquiry's email to resolve to an active member of
 *       the workspace it is given, so it is safe even when called with an id that never came from
 *       {@link #findExistingAccountForEmail}. Do not remove that check on the grounds that "the
 *       console only ever passes what the read returned" — the console is not the security
 *       boundary, and this endpoint is reachable without it.
 *   <li>[Kabir M-5] The read answers "is there an account here, and roughly how established is
 *       it", never "here are that brand's identifiers". The email match is UNVERIFIED by
 *       construction — a collision is the case this endpoint exists to surface — so anything it
 *       returns may describe an unrelated brand, and its response shape is chosen with that in
 *       mind. Adding a field here means asking whether it would be acceptable to disclose to
 *       someone the workspace has nothing to do with.
 * </ul>
 */
@Service
public class FestivalSponsorProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(FestivalSponsorProvisioningService.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration PASSWORD_SET_LINK_TTL = Duration.ofDays(7);

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final FestivalEnquiryRepository festivalEnquiryRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WalletRepository walletRepository;
    private final CampaignRepository campaignRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${influora.web-base-url}")
    private String webBaseUrl;

    public FestivalSponsorProvisioningService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            FestivalEnquiryRepository festivalEnquiryRepository,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WalletRepository walletRepository,
            CampaignRepository campaignRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            ApplicationEventPublisher eventPublisher) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.festivalEnquiryRepository = festivalEnquiryRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.walletRepository = walletRepository;
        this.campaignRepository = campaignRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ProvisionFestivalSponsorResponse provision(
            AuthPrincipal principal, HttpServletRequest httpRequest, String enquiryId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        // PESSIMISTIC_WRITE, held to commit by this @Transactional — see class javadoc on why
        // this is the whole fix for double-clicking "Provision" and why a released-early named
        // lock (F-0650's first, broken fix) is not an equivalent shape.
        FestivalEnquiry enquiry =
                festivalEnquiryRepository
                        .findByIdForUpdate(enquiryId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ENQUIRY_NOT_FOUND", "Enquiry not found", HttpStatus.NOT_FOUND));

        if (enquiry.getType() != FestivalEnquiryType.BRAND) {
            throw new ApiException(
                    "ENQUIRY_NOT_BRAND",
                    "Only BRAND enquiries can be provisioned as a sponsor",
                    HttpStatus.BAD_REQUEST);
        }
        if (enquiry.getStatus() != FestivalEnquiryStatus.WON) {
            throw new ApiException(
                    "ENQUIRY_NOT_WON",
                    "Enquiry must be WON before it can be provisioned",
                    HttpStatus.BAD_REQUEST);
        }
        // The idempotency check: provisionedWorkspaceId is null until this method's own
        // festivalEnquiryRepository.save(...) call below sets it, inside this same transaction.
        if (enquiry.getProvisionedWorkspaceId() != null) {
            throw new ApiException(
                    "ALREADY_PROVISIONED",
                    "This enquiry has already been provisioned",
                    HttpStatus.CONFLICT);
        }
        // Upfront check narrows the common case; the same TOCTOU race AuthService#brandRegister
        // documents is closed below by the saveAndFlush + catch, not by this check alone.
        if (userRepository.existsByEmailIgnoreCase(enquiry.getEmail())) {
            throw new ApiException(
                    "EMAIL_ALREADY_REGISTERED",
                    "An account with this email already exists — this cannot be attached to an"
                            + " existing account automatically",
                    HttpStatus.CONFLICT);
        }

        String[] name = splitName(enquiry.getName());
        String userId = Ulids.newUlid();
        User user =
                User.newBrand(
                        userId,
                        enquiry.getEmail(),
                        passwordEncoder.encode(generateHighEntropyPassword()),
                        name[0],
                        name[1],
                        enquiry.getName());

        // WITHOUT THIS LINE THE ENTIRE PROVISIONING FEATURE IS DEAD ON ARRIVAL. Do not remove it
        // without reading AuthService#login's guard first.
        //
        // login() refuses with 403 EMAIL_NOT_VERIFIED when ALL THREE of these hold:
        //     requireEmailVerification && !user.isEmailVerified() && status == PENDING_VERIFICATION
        // `influora.auth.require-email-verification` is a hardcoded literal `true` in
        // application.yml (not even env-overridable), and User.newBrand sets emailVerified=false
        // AND status=PENDING_VERIFICATION. So every provisioned brand matched all three: they would
        // receive the password-set email, set a password, and then be refused at login forever,
        // with no self-service way out — the "resend verification" path keys off a registration
        // they never performed.
        //
        // Marking the address verified here is not a shortcut around that check, it is the same
        // proof by a different route. The account is created with an UNUSABLE random password
        // (above), so the ONLY way it can ever be signed into is by consuming the single-use token
        // we email to this exact address. Control of the mailbox is therefore proven before the
        // first successful login by construction — strictly stronger than the OTP path, which
        // proves the same thing before a password exists at all.
        user.setEmailVerified(true);

        String workspaceId = Ulids.newUlid();
        String slug = uniqueSlug(enquiry.getCompany());
        Workspace workspace =
                Workspace.newBrand(
                        workspaceId, enquiry.getCompany(), slug, enquiry.getProductCategory(), null);

        try {
            // Insert order matters — see class javadoc. user/workspace must land first because
            // workspace_members/wallets carry hard FK-id columns on users.id/workspaces.id that
            // Hibernate does not reorder across entity types.
            userRepository.saveAndFlush(user);
            workspaceRepository.save(workspace);
            workspaceMemberRepository.save(WorkspaceMember.owner(Ulids.newUlid(), workspaceId, userId));
            walletRepository.save(Wallet.forWorkspace(Ulids.newUlid(), workspaceId));
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "EMAIL_ALREADY_REGISTERED",
                    "An account with this email already exists — this cannot be attached to an"
                            + " existing account automatically",
                    HttpStatus.CONFLICT);
        }

        String campaignId = Ulids.newUlid();
        Campaign campaign =
                Campaign.builder()
                        .id(campaignId)
                        .workspaceId(workspaceId)
                        .title(campaignTitle(enquiry))
                        .status(CampaignStatus.DRAFT)
                        .createdBy(userId)
                        .build();
        campaignRepository.save(campaign);

        issuePasswordSetLink(userId, enquiry.getEmail(), workspaceId);

        Instant provisionedAt = Instant.now();
        enquiry.markProvisioned(userId, workspaceId, campaignId, provisionedAt);
        festivalEnquiryRepository.save(enquiry);

        // Audit-logged AFTER every row above is saved — the acting admin is identified only via
        // `principal` here, never written into any users.id-FK column (workspace_member_invites'
        // invited_by shape is exactly why: see this feature's design doc).
        adminAuditLogService.record(
                principal,
                httpRequest,
                "CREATE",
                "FESTIVAL_SPONSOR_PROVISIONING",
                enquiry.getId(),
                Map.of("enquiryId", enquiry.getId(), "status", enquiry.getStatus().name()),
                Map.of(
                        "userId", userId,
                        "workspaceId", workspaceId,
                        "campaignId", campaignId),
                null);

        log.info(
                "Festival enquiry {} provisioned as sponsor -> user={} workspace={} campaign={}",
                enquiry.getId(),
                userId,
                workspaceId,
                campaignId);

        return new ProvisionFestivalSponsorResponse(
                enquiry.getId(), userId, workspaceId, campaignId, provisionedAt.toString());
    }

    /**
     * Phase 8 — links a {@code WON}/{@code BRAND} enquiry to a workspace that ALREADY exists
     * (a returning sponsor), instead of creating a new {@link User}/{@link Workspace}/{@link
     * Wallet} the way {@link #provision} does. Backs {@code POST
     * /admin/festival-enquiries/{id}/link-existing}. See the class javadoc "Phase 8" section for
     * why this is a second, explicit action rather than a loosened {@link #provision}.
     *
     * <p>Same role gate, same {@code PESSIMISTIC_WRITE} lock via {@link
     * FestivalEnquiryRepository#findByIdForUpdate} held to commit by this method's own {@code
     * @Transactional}, and the same {@code provisionedWorkspaceId == null} idempotency check as
     * {@link #provision} — see {@code F-0650-provisioning-lock-commit-order.sh}'s header for why
     * the lock must be held to commit rather than released inside the callback. A second
     * concurrent call (whether it is another {@code link-existing} or a {@code provision}) blocks
     * on that same SELECT until this transaction commits, then sees {@code provisionedWorkspaceId}
     * already set and returns 409 {@code ALREADY_PROVISIONED}.
     *
     * <p><b>{@code workspaceId} is admin-CHOSEN but server-VERIFIED.</b> [Kabir H-2] This javadoc
     * previously read "admin-supplied, not derived from the enquiry's email here", and argued that
     * "trusting only an explicit admin choice for the write keeps this action auditable and
     * deliberate". Deliberate it was; verified it was not. Any BRAND workspace on the platform
     * could be named and this method would attach a campaign to it, because nothing tied the
     * supplied id back to the enquiry. Auditability records a wrong write, it does not prevent one.
     *
     * <p>The write now requires the enquiry's own email to resolve to a user with an ACTIVE
     * membership in the target workspace — the same relationship {@link
     * #findExistingAccountForEmail} reads, enforced here so it holds regardless of what the caller
     * passes. The admin still picks (a returning contact may belong to several workspaces, and only
     * a human knows which one this edition belongs under); the pick is simply constrained to
     * workspaces the enquiry can prove a relationship to. Failing that check is {@code 400
     * WORKSPACE_NOT_LINKED_TO_ENQUIRY}.
     *
     * <p><b>What this deliberately no longer supports:</b> a returning brand whose enquiry was
     * submitted by a DIFFERENT colleague than the one holding the existing account can no longer be
     * linked here, because the new contact's email is not a member of the old workspace. That case
     * was never this endpoint's purpose — it exists to make {@link #provision}'s {@code
     * EMAIL_ALREADY_REGISTERED} refusal actionable, and a new colleague's address does not trigger
     * that refusal at all ({@code provision} simply succeeds and creates a fresh workspace). An
     * admin who genuinely wants the new contact on the old workspace should invite them to it
     * first, through the normal workspace-invite flow; the link then satisfies this check honestly,
     * because the relationship actually exists. Do not relax this check to restore the shortcut —
     * "the admin knows these two are the same company" is precisely the assertion that has no
     * server-side evidence behind it.
     *
     * <p>Only a new {@code DRAFT} {@link Campaign} is created. {@code created_by} is filled with
     * the target workspace's OWNER member — the campaign has to belong to SOMEONE per the
     * {@code campaigns.created_by NOT NULL} column, and the workspace's actual owner is the
     * honest value, not a fabricated one; no new user is created and {@code provisionedUserId} on
     * the enquiry is left {@code null} because none was created here.
     */
    @Transactional
    public LinkExistingSponsorResponse linkExisting(
            AuthPrincipal principal, HttpServletRequest httpRequest, String enquiryId, String workspaceId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        // Same PESSIMISTIC_WRITE lock, held to commit, as provision() — see that method's inline
        // comment and the class javadoc for why.
        FestivalEnquiry enquiry =
                festivalEnquiryRepository
                        .findByIdForUpdate(enquiryId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ENQUIRY_NOT_FOUND", "Enquiry not found", HttpStatus.NOT_FOUND));

        if (enquiry.getType() != FestivalEnquiryType.BRAND) {
            throw new ApiException(
                    "ENQUIRY_NOT_BRAND",
                    "Only BRAND enquiries can be linked to an existing sponsor workspace",
                    HttpStatus.BAD_REQUEST);
        }
        if (enquiry.getStatus() != FestivalEnquiryStatus.WON) {
            throw new ApiException(
                    "ENQUIRY_NOT_WON",
                    "Enquiry must be WON before it can be linked to an existing sponsor workspace",
                    HttpStatus.BAD_REQUEST);
        }
        // Same idempotency marker provision() checks — see this method's javadoc.
        if (enquiry.getProvisionedWorkspaceId() != null) {
            throw new ApiException(
                    "ALREADY_PROVISIONED",
                    "This enquiry has already been provisioned",
                    HttpStatus.CONFLICT);
        }

        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
        if (workspace.getType() != WorkspaceType.BRAND) {
            throw new ApiException(
                    "WORKSPACE_NOT_BRAND",
                    "Only a BRAND workspace can be linked as a sponsor",
                    HttpStatus.BAD_REQUEST);
        }

        // [Kabir H-2] THE SERVER-SIDE BINDING BETWEEN THIS ENQUIRY AND THIS WORKSPACE.
        //
        // Until this check, workspaceId was accepted on the caller's word alone: any BRAND
        // workspace in the system could be named, and this method would attach a campaign titled
        // after THIS enquiry's company to it and stamp the enquiry as provisioned against it. The
        // method javadoc argued that was fine because the choice is "explicit and auditable" — but
        // auditable is not the same as correct. An audit log records who made the wrong write; it
        // does not prevent it. The realistic failure is not an attack, it is a mis-click or a
        // stale id in an admin console listing several similarly-named brands, and its result is
        // an unrelated paying brand silently acquiring a DRAFT campaign named after a competitor,
        // plus an enquiry permanently marked provisioned against the wrong workspace (the
        // provisionedWorkspaceId idempotency marker means there is no second attempt to get right).
        //
        // The binding is the same relationship findExistingAccountForEmail computes: the enquiry's
        // own email must belong to a user who is an ACTIVE member of the target workspace. That is
        // derived entirely from the enquiry row, so the caller cannot assert it.
        //
        // This does NOT reduce the action to auto-detection — the admin still chooses, which
        // matters because a returning sponsor's contact may belong to several workspaces and only
        // a human knows which one this edition belongs under. What changed is that the choice is
        // now constrained to workspaces the enquiry can actually prove a relationship to, instead
        // of to every workspace on the platform.
        User enquiryUser =
                userRepository
                        .findByEmailIgnoreCase(enquiry.getEmail())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "NO_EXISTING_ACCOUNT",
                                                "No existing account is registered for this enquiry's email, so"
                                                        + " there is nothing to link it to. Provision a new sponsor"
                                                        + " account instead.",
                                                HttpStatus.BAD_REQUEST));

        workspaceMemberRepository
                .findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, enquiryUser.getId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "WORKSPACE_NOT_LINKED_TO_ENQUIRY",
                                        "This enquiry's contact is not an active member of that workspace, so the"
                                                + " two cannot be linked",
                                        HttpStatus.BAD_REQUEST));

        // The campaign row needs a non-null created_by; the workspace's own OWNER member is the
        // honest attribution here (no new user was created for this action).
        WorkspaceMember owner =
                workspaceMemberRepository
                        .findFirstByWorkspaceIdAndRoleAndActiveTrue(workspaceId, MemberRole.OWNER)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_OWNER_NOT_FOUND",
                                                "Target workspace has no active owner to attribute the new campaign"
                                                        + " to",
                                                HttpStatus.BAD_REQUEST));

        String campaignId = Ulids.newUlid();
        Campaign campaign =
                Campaign.builder()
                        .id(campaignId)
                        .workspaceId(workspaceId)
                        .title(campaignTitle(enquiry))
                        .status(CampaignStatus.DRAFT)
                        .createdBy(owner.getUserId())
                        .build();
        campaignRepository.save(campaign);

        Instant provisionedAt = Instant.now();
        // provisionedUserId is deliberately null — no User was created by this action, and
        // back-filling the workspace owner's id would misrepresent what happened.
        enquiry.markProvisioned(null, workspaceId, campaignId, provisionedAt);
        festivalEnquiryRepository.save(enquiry);

        adminAuditLogService.record(
                principal,
                httpRequest,
                "CREATE",
                "FESTIVAL_SPONSOR_PROVISIONING",
                enquiry.getId(),
                Map.of("enquiryId", enquiry.getId(), "status", enquiry.getStatus().name()),
                Map.of(
                        "workspaceId", workspaceId,
                        "campaignId", campaignId,
                        "linkedExisting", true),
                null);

        log.info(
                "Festival enquiry {} linked to existing sponsor workspace={} -> campaign={}",
                enquiry.getId(),
                workspaceId,
                campaignId);

        return new LinkExistingSponsorResponse(
                enquiry.getId(), workspaceId, campaignId, provisionedAt.toString(), true);
    }

    /**
     * Read-only companion to {@link #provision}'s {@code EMAIL_ALREADY_REGISTERED} refusal —
     * backs {@code GET /admin/festival-enquiries/{id}/existing-account}. Resolves the enquiry's
     * email to whatever existing user/workspace already holds it, so the admin console can offer
     * "link to the existing workspace instead" and pass the returned {@code workspaceId} straight
     * into {@link #linkExisting}.
     *
     * <p>No row lock: this never writes anything, so there is nothing for a
     * {@code PESSIMISTIC_WRITE} to protect here.
     */
    public ExistingSponsorAccountResponse findExistingAccountForEmail(
            AuthPrincipal principal, String enquiryId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        FestivalEnquiry enquiry =
                festivalEnquiryRepository
                        .findById(enquiryId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ENQUIRY_NOT_FOUND", "Enquiry not found", HttpStatus.NOT_FOUND));

        User existingUser =
                userRepository
                        .findByEmailIgnoreCase(enquiry.getEmail())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "NO_EXISTING_ACCOUNT",
                                                "No existing account is registered for this enquiry's email",
                                                HttpStatus.NOT_FOUND));

        WorkspaceMember membership =
                workspaceMemberRepository
                        .findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(existingUser.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "NO_EXISTING_WORKSPACE",
                                                "The existing account for this email has no active workspace"
                                                        + " membership",
                                                HttpStatus.NOT_FOUND));

        Workspace workspace =
                workspaceRepository
                        .findById(membership.getWorkspaceId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "NO_EXISTING_WORKSPACE",
                                                "The existing account's workspace could not be resolved",
                                                HttpStatus.NOT_FOUND));

        // [Kabir M-5] hasMetaPixelId, NOT the id. See ExistingSponsorAccountResponse's javadoc: the
        // raw id corroborated nothing an admin could check, and this endpoint's whole purpose is
        // the case where the email match may belong to an unrelated brand.
        return new ExistingSponsorAccountResponse(
                existingUser.getId(),
                workspace.getId(),
                workspace.getName(),
                workspace.getMetaPixelId() != null && !workspace.getMetaPixelId().isBlank());
    }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------

    /**
     * Builds the emailed password-set link and publishes the existing {@link PasswordResetEvent}
     * so the brand actually receives it — {@code NotificationListener} already routes that event
     * to an email-only "auth.password_reset" template; there is no separate "set your password"
     * template in this codebase, and this reuses that one rather than inventing a new template
     * for a flow that is, mechanically, identical (follow a link, set a password).
     *
     * <p>7-day expiry, NOT the 1-hour forgot-password uses ({@code AuthService#createPasswordResetToken})
     * — this is an onboarding handoff a busy brand contact may not open same-day.
     */
    private void issuePasswordSetLink(String userId, String email, String workspaceId) {
        String raw = jwtService.createRefreshTokenValue();
        String hash = JwtService.hashToken(raw);
        String tokenId = Ulids.newUlid();
        passwordResetTokenRepository.save(
                PasswordResetToken.create(
                        tokenId, userId, hash, Instant.now().plus(PASSWORD_SET_LINK_TTL)));

        String resetLink = webBaseUrl + "/reset-password?token=" + raw;
        eventPublisher.publishEvent(
                new PasswordResetEvent(userId, workspaceId, tokenId, email, resetLink));
    }

    /** Copies {@code AuthService#uniqueSlug}'s approach exactly (that method is private there). */
    private String uniqueSlug(String company) {
        String base = SlugUtils.slugify(company);
        String slug = base;
        int attempt = 0;
        while (workspaceRepository.existsBySlug(slug)) {
            attempt++;
            slug = base + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
            if (attempt > 20) {
                slug = base + "-" + Ulids.newUlid().substring(0, 6).toLowerCase(Locale.ROOT);
                break;
            }
        }
        return slug;
    }

    /**
     * A 256-bit random value, base64url-encoded, run through {@link PasswordEncoder#encode} and
     * then discarded — never stored, logged, or returned anywhere. No known password can ever
     * hash to the result: it is drawn from {@link SecureRandom}, not derived from anything the
     * brand contact (or anyone else) could type.
     */
    private static String generateHighEntropyPassword() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** First token of the enquiry's free-text name is firstName; the remainder is lastName. */
    private static String[] splitName(String fullName) {
        String trimmed = fullName == null ? "" : fullName.trim();
        if (trimmed.isEmpty()) {
            return new String[] {"Sponsor", ""};
        }
        int idx = trimmed.indexOf(' ');
        if (idx < 0) {
            return new String[] {trimmed, ""};
        }
        return new String[] {trimmed.substring(0, idx), trimmed.substring(idx + 1).trim()};
    }

    /**
     * e.g. {@code edition = "MUMBAI_FESTIVE_2026"}, {@code company = "Acme Corp"} ->
     * {@code "Festival Box — Mumbai Festive 2026 — Acme Corp"}.
     */
    private static String campaignTitle(FestivalEnquiry enquiry) {
        return "Festival Box — " + humanizeEdition(enquiry.getEdition()) + " — " + enquiry.getCompany();
    }

    private static String humanizeEdition(String edition) {
        if (edition == null || edition.isBlank()) {
            return "Sponsor Campaign";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : edition.trim().split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.length() == 0 ? "Sponsor Campaign" : sb.toString();
    }
}
