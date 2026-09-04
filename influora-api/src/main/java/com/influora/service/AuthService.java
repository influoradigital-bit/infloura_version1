package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.PasswordPolicy;
import com.influora.common.SlugUtils;
import com.influora.common.Ulids;
import com.influora.config.InfluoraEnvironment;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PasswordResetToken;
import com.influora.domain.entity.RefreshToken;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PasswordResetTokenRepository;
import com.influora.repository.RefreshTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.JwtService;
import com.influora.service.notification.event.PasswordResetEvent;
import com.influora.service.notification.event.UserCreatedEvent;
import com.influora.web.dto.auth.AuthDtos.TokenPair;
import com.influora.web.dto.auth.AuthDtos.UserDto;
import com.influora.web.dto.auth.AuthDtos.WorkspaceDto;
import com.influora.web.dto.auth.BrandRegisterRequest;
import com.influora.web.dto.auth.CreatorRegisterRequest;
import com.influora.web.dto.auth.LoginRequest;
import java.time.Instant;
import java.util.List;
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

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final WalletRepository walletRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final JwtService jwtService;
    private final BrandEmailOtpService brandEmailOtpService;
    private final InfluoraEnvironment environment;
    private final ApplicationEventPublisher eventPublisher;
    private final RegistrationService registrationService;
    private final UserPhoneService userPhoneService;

    @Value("${influora.auth.require-email-verification:true}")
    private boolean requireEmailVerification;

    @Value("${influora.auth.require-email-otp-before-register:false}")
    private boolean requireEmailOtpBeforeRegister;

    @Value("${influora.web-base-url}")
    private String webBaseUrl;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            WalletRepository walletRepository,
            CreatorProfileRepository creatorProfileRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            BrandEmailOtpService brandEmailOtpService,
            InfluoraEnvironment environment,
            ApplicationEventPublisher eventPublisher,
            RegistrationService registrationService,
            UserPhoneService userPhoneService) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.walletRepository = walletRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.brandEmailOtpService = brandEmailOtpService;
        this.environment = environment;
        this.eventPublisher = eventPublisher;
        this.registrationService = registrationService;
        this.userPhoneService = userPhoneService;
    }

    /**
     * <p><b>TOCTOU race [E2 audit finding #3, MEDIUM — fixed]</b> — {@code users.email} already
     * has a DB-level {@code UNIQUE} constraint ({@code V2__core_auth.sql}), so two concurrent
     * registrations for the same email can never both create a row; the database, not the
     * check below, is what actually prevents duplicates. But before this fix, the LOSING
     * concurrent request's final {@code save()} threw an unhandled {@link
     * DataIntegrityViolationException} that fell through to {@code GlobalExceptionHandler}'s
     * generic handler as a raw {@code 500 INTERNAL_ERROR} instead of the friendly {@code 409
     * EMAIL_ALREADY_EXISTS} the sequential (non-racing) path already returns above. The save
     * sequence is now wrapped in a try/catch that translates that constraint violation into the
     * same {@code ApiException} the upfront {@code existsByEmailIgnoreCase} check throws, so a
     * double-click "Sign Up" or any client retry on network ambiguity gets the correct 409
     * either way.
     */
    @Transactional
    public TokenPair brandRegister(BrandRegisterRequest req) {
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException("EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
        }
        PasswordPolicy.validate(req.password());

        // PHONE-0904 Q8 ruling (Swapnil) — brand mobile is now REQUIRED, not merely a UI
        // convention (BrandRegisterRequest's javadoc explains why this is a service-level check
        // and not @NotBlank: it needs its OWN machine-readable code, distinct from both
        // INVALID_PHONE below and a bare Bean-Validation 400, so Ananya's client can put an
        // inline message on the phone field specifically — the exact gap Q6 raised). This check
        // deliberately runs BEFORE normalizeAndValidate so "missing" and "malformed" never
        // collapse into the same code.
        if (req.phone() == null || req.phone().isBlank()) {
            throw new ApiException(
                    "PHONE_REQUIRED", "Phone number is required", HttpStatus.BAD_REQUEST);
        }

        // PHONE-0829 Gap A / PHONE-0904 — normalized and validated against the same strict
        // Indian-mobile rule as every other phone-capture call site (shared via UserPhoneService,
        // see its javadoc for why brandRegister can't just call its applyPhone(User, String)
        // directly) BEFORE the user row is ever built, so a bad or duplicate phone never gets as
        // far as allocating a userId/workspace. Same upfront-check convention as the email dup
        // check just above: users.phone_number is UNIQUE, so this narrows the common case, but
        // the try/catch below (mirroring the email race-loser handling documented on this method)
        // is what actually guarantees a clean 409 rather than a raw 500 on a race.
        String normalizedPhone = userPhoneService.normalizeAndValidate(req.phone());
        if (userPhoneService.isTaken(normalizedPhone)) {
            // The response code here is PHONE_ALREADY_EXISTS with a message that names the phone
            // number specifically (never EMAIL_ALREADY_EXISTS's wording) so a caller on a shared
            // number (family/agency line, or an existing CREATOR account) knows unambiguously
            // which identifier collided and what to change — see this method's own javadoc on why
            // that distinction matters now that the field is mandatory and has no dead-end.
            throw new ApiException(
                    "PHONE_ALREADY_EXISTS",
                    "An account with this phone number already exists",
                    HttpStatus.CONFLICT);
        }

        String displayName = (req.firstName() + " " + req.lastName()).trim();
        String userId = Ulids.newUlid();
        User user =
                User.newBrand(
                        userId,
                        req.email(),
                        passwordEncoder.encode(req.password()),
                        req.firstName(),
                        req.lastName(),
                        displayName);
        // normalizedPhone can never be null here -- the PHONE_REQUIRED guard above already
        // rejected a missing/blank value before this point.
        user.setPhoneNumber(normalizedPhone);

        if (requireEmailOtpBeforeRegister) {
            // Throws if the email hasn't completed OTP verification -- only reachable
            // past this point once ownership is actually proven, so it's safe to mark
            // the account verified here (Priya/Kabir gap fix: this used to run
            // unconditionally, making the login-time verification gate permanently dead).
            brandEmailOtpService.requireVerifiedEmail(req.email());
            user.setEmailVerified(true);
        }

        String workspaceId = Ulids.newUlid();
        String slug = uniqueSlug(req.companyName());
        Workspace workspace =
                Workspace.newBrand(workspaceId, req.companyName(), slug, req.industry(), req.companySize());

        try {
            // Insert order matters: workspace_members and wallets carry hard FK constraints on
            // users.id / workspaces.id (fk_wm_user, fk_wm_workspace, ...), and Hibernate only
            // reorders inserts across entity types when they're mapped as JPA associations -- these
            // are plain FK-id columns, so it flushes in call order. user/workspace must land first.
            // saveAndFlush on the user row (the one with the UNIQUE(email) constraint) is what makes
            // the catch block below actually able to see the race documented in the class comment
            // above -- an unflushed save() here would defer the constraint check to the surrounding
            // @Transactional's commit, which happens after this catch block's scope has already
            // exited, letting the violation escape as an uncaught 500 instead of the intended 409.
            userRepository.saveAndFlush(user);
            workspaceRepository.save(workspace);
            workspaceMemberRepository.save(WorkspaceMember.owner(Ulids.newUlid(), workspaceId, userId));
            walletRepository.save(Wallet.forWorkspace(Ulids.newUlid(), workspaceId));
        } catch (DataIntegrityViolationException dup) {
            // PHONE-0829 Gap A — the upfront existsByPhoneNumber check above narrows the common
            // case but doesn't close the race (same TOCTOU shape as email); re-check here so a
            // raced duplicate PHONE surfaces its own accurate 409 instead of the misleading
            // EMAIL_ALREADY_EXISTS every other constraint violation on this save still falls back
            // to (unchanged from before this fix).
            if (userPhoneService.isTaken(normalizedPhone)) {
                throw new ApiException(
                        "PHONE_ALREADY_EXISTS",
                        "An account with this phone number already exists",
                        HttpStatus.CONFLICT);
            }
            throw new ApiException(
                    "EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
        }

        // W3-1 — #18 "welcome after signup" (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). Genuinely open
        // before this change: brandRegister/creatorRegister had no publisher for UserCreatedEvent
        // anywhere (grep 0 refs at a call site), so the listener already wired in
        // NotificationListener never fired.
        eventPublisher.publishEvent(new UserCreatedEvent(userId, workspaceId, userId, displayName, "brand"));

        return issueTokens(user, workspace);
    }

    @Transactional
    public TokenPair brandLogin(LoginRequest req) {
        User user =
                userRepository
                        .findByEmailIgnoreCase(req.email())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_CREDENTIALS",
                                                "Invalid email or password",
                                                HttpStatus.UNAUTHORIZED));

        if (user.getUserType() != UserType.BRAND) {
            throw new ApiException("WRONG_USER_TYPE", "This endpoint is for brand accounts only", HttpStatus.FORBIDDEN);
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DEACTIVATED) {
            throw new ApiException("ACCOUNT_SUSPENDED", "Your account has been suspended", HttpStatus.FORBIDDEN);
        }

        if (requireEmailVerification
                && !user.isEmailVerified()
                && user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new ApiException(
                    "EMAIL_NOT_VERIFIED", "Please verify your email before signing in", HttpStatus.FORBIDDEN);
        }

        // H-16: deterministic (oldest-first) so the same user always lands in the same workspace
        // across logins, instead of an arbitrary one when they hold ≥2 active memberships.
        // F-0458: and SKIP suspended workspaces rather than hard-failing on the oldest one. The
        // first cut of F-0451 resolved exactly one membership and threw if it was suspended, which
        // locked a multi-workspace user out of workspaces the admin never touched — the precise
        // outcome the comment below says must not happen. Landing order is still oldest-first among
        // the workspaces the user may actually enter.
        List<WorkspaceMember> memberships =
                workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId());
        if (memberships.isEmpty()) {
            throw new ApiException(
                    "WORKSPACE_NOT_FOUND", "No workspace found for this user", HttpStatus.NOT_FOUND);
        }

        Workspace workspace = firstEnterableWorkspace(memberships);

        user.markLogin();
        userRepository.save(user);
        return issueTokens(user, workspace);
    }

    /**
     * F-0451/F-0458 — resolve the workspace a user should land in, skipping suspended ones.
     *
     * <p>Admin suspend writes {@code workspaces.is_suspended}, and no authentication path read it:
     * a suspended brand simply logged in again and kept operating. Suspension is deliberately NOT
     * cascaded to {@code users.status}, because a Workspace has many members and a member may
     * belong to other, unsuspended workspaces — disabling the person's account would punish the
     * wrong party. So the flag is enforced per-workspace here instead.
     *
     * <p>Throws only when EVERY active membership is suspended, which is the one case where there
     * is genuinely nowhere for the user to go. Same code and message as
     * {@code WorkspaceMemberService.switchWorkspace}, so the client sees one behaviour whichever
     * route it arrives by.
     */
    private Workspace firstEnterableWorkspace(List<WorkspaceMember> memberships) {
        Workspace firstFound = null;
        for (WorkspaceMember m : memberships) {
            Workspace ws = workspaceRepository.findById(m.getWorkspaceId()).orElse(null);
            if (ws == null) {
                continue;
            }
            if (firstFound == null) {
                firstFound = ws;
            }
            if (!ws.isSuspended()) {
                return ws;
            }
        }
        if (firstFound == null) {
            throw new ApiException(
                    "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND);
        }
        throw new ApiException(
                "WORKSPACE_SUSPENDED",
                "This workspace has been suspended. Contact support.",
                HttpStatus.FORBIDDEN);
    }

    @Transactional
    public TokenPair creatorRegister(CreatorRegisterRequest req) {
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException("EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
        }
        PasswordPolicy.validate(req.password());

        String firstName;
        String lastName;
        String displayName;
        if (req.displayName() != null && !req.displayName().isBlank()) {
            displayName = req.displayName().trim();
            firstName =
                    req.firstName() != null && !req.firstName().isBlank()
                            ? req.firstName().trim()
                            : displayName;
            lastName = req.lastName() != null && !req.lastName().isBlank() ? req.lastName().trim() : "";
        } else {
            firstName = req.firstName().trim();
            lastName = req.lastName().trim();
            displayName = (firstName + " " + lastName).trim();
        }

        String userId = Ulids.newUlid();
        User user =
                User.newCreator(
                        userId,
                        req.email(),
                        passwordEncoder.encode(req.password()),
                        firstName,
                        lastName,
                        displayName);

        if (requireEmailOtpBeforeRegister) {
            brandEmailOtpService.requireVerifiedEmail(req.email());
            user.setEmailVerified(true);
        }

        String profileId = Ulids.newUlid();
        CreatorProfile profile = CreatorProfile.newForUser(profileId, userId, displayName);

        try {
            userRepository.saveAndFlush(user);
            creatorProfileRepository.save(profile);
            walletRepository.save(Wallet.forUser(Ulids.newUlid(), userId));
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
        }

        // Q5.5 (T-CREATORCONNECT-0902, Medium) — an invited creator who registers by email
        // (rather than connecting Meta first) links to their external_creators row here, right
        // after the CreatorProfile id exists. req.inviteToken() is null/blank for an ordinary
        // (non-invite) registration; RegistrationService#consumeInviteToken treats that as a safe
        // no-op and never throws — a bad/expired/already-consumed invite token must never fail
        // account creation itself.
        registrationService.consumeInviteToken(req.inviteToken(), profileId);

        // W3-1 — #18 "welcome after signup", same gap as brandRegister above.
        eventPublisher.publishEvent(new UserCreatedEvent(userId, null, userId, displayName, "creator"));

        return issueTokens(user, null);
    }

    @Transactional
    public TokenPair creatorLogin(LoginRequest req) {
        User user =
                userRepository
                        .findByEmailIgnoreCase(req.email())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_CREDENTIALS",
                                                "Invalid email or password",
                                                HttpStatus.UNAUTHORIZED));

        if (user.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DEACTIVATED) {
            throw new ApiException("ACCOUNT_SUSPENDED", "Your account has been suspended", HttpStatus.FORBIDDEN);
        }

        if (requireEmailVerification
                && !user.isEmailVerified()
                && user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new ApiException(
                    "EMAIL_NOT_VERIFIED", "Please verify your email before signing in", HttpStatus.FORBIDDEN);
        }

        // F-0451: creator_profiles.is_suspended was read only by CreatorDiscoveryService (:900,:918,
        // :929) and DealService (:223) -- i.e. it hid the creator from the marketplace but never
        // stopped them signing in. A creator profile is 1:1 with a user (CreatorProfile.user_id is
        // unique), but the flag stays the single source of truth rather than being mirrored onto
        // users.status: two flags that can disagree is how a reinstate ends up restoring the wrong
        // state (ACTIVE vs PENDING_VERIFICATION).
        creatorProfileRepository
                .findByUserId(user.getId())
                .filter(CreatorProfile::isSuspended)
                .ifPresent(
                        p -> {
                            throw new ApiException(
                                    "ACCOUNT_SUSPENDED",
                                    "Your account has been suspended",
                                    HttpStatus.FORBIDDEN);
                        });

        user.markLogin();
        userRepository.save(user);
        return issueTokens(user, null);
    }

    /**
     * Result of a refresh: a new access token plus a freshly-rotated refresh token. The controller
     * puts {@code newRefreshToken} into the HttpOnly cookie and returns only {@code access}/
     * {@code expiresIn} in the body (Kabir A1 — the refresh token never reaches JS).
     */
    public record RefreshRotation(String accessToken, long expiresIn, String newRefreshToken) {}

    @Transactional
    public RefreshRotation refresh(String rawRefreshToken) {
        String hash = JwtService.hashToken(rawRefreshToken);
        RefreshToken stored =
                refreshTokenRepository
                        .findByTokenHashAndRevokedFalse(hash)
                        .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_REFRESH_TOKEN",
                                                "Refresh token is invalid or expired",
                                                HttpStatus.UNAUTHORIZED));

        User user =
                userRepository
                        .findById(stored.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.UNAUTHORIZED));

        // AUTH-1 (Priya/Kavya gap): refresh() previously validated only token state (not revoked,
        // not expired) plus user existence, never the account-state gates brandLogin/creatorLogin
        // enforce. Two consequences without these: (1) revokeAllForUser is only called from
        // logout/resetPassword, so an admin suspension never revoked outstanding refresh tokens --
        // a SUSPENDED/DEACTIVATED account could keep minting fresh access tokens indefinitely on
        // the 30-day rotating window; (2) register() returns a live TokenPair pre-verification, and
        // refresh never re-checked, so an unverified account held an indefinitely renewable session.
        // Mirrors brandLogin (:193-202) / creatorLogin (:308-316) exactly -- same codes, same
        // statuses, same requireEmailVerification semantics. Placed BEFORE the revoke/rotate block
        // below on purpose: a rejected refresh must not burn the presented token (see class-level
        // reasoning) -- a suspended user retrying should see ACCOUNT_SUSPENDED, not a misleading
        // INVALID_REFRESH_TOKEN on their next attempt because we'd already revoked the token they
        // presented.
        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DEACTIVATED) {
            throw new ApiException("ACCOUNT_SUSPENDED", "Your account has been suspended", HttpStatus.FORBIDDEN);
        }

        if (requireEmailVerification
                && !user.isEmailVerified()
                && user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new ApiException(
                    "EMAIL_NOT_VERIFIED", "Please verify your email before signing in", HttpStatus.FORBIDDEN);
        }

        // H-16: same deterministic ordering as brandLogin — a refresh must not silently move the
        // caller to a different workspace than the one their access token already carried.
        WorkspaceMember member =
                workspaceMemberRepository
                        .findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId())
                        .orElse(null);

        // F-0451: without these, the login gates above are a 15-minute speed bump -- refresh tokens
        // rotate on a 30-day window, so a suspended brand or creator kept minting fresh access
        // tokens indefinitely. Same placement rationale as the AUTH-1 block above: BEFORE the
        // revoke/rotate, so a rejected refresh does not burn the presented token and the caller
        // sees WORKSPACE_SUSPENDED/ACCOUNT_SUSPENDED rather than INVALID_REFRESH_TOKEN on retry.
        // F-0458: resolved through the same skip-suspended helper as brandLogin, so a refresh can
        // move a multi-workspace user off a newly-suspended workspace instead of locking them out.
        String workspaceId = null;
        if (member != null) {
            workspaceId =
                    firstEnterableWorkspace(
                                    workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(
                                            user.getId()))
                            .getId();
        }
        if (user.getUserType() == UserType.CREATOR) {
            creatorProfileRepository
                    .findByUserId(user.getId())
                    .filter(CreatorProfile::isSuspended)
                    .ifPresent(
                            p -> {
                                throw new ApiException(
                                        "ACCOUNT_SUSPENDED",
                                        "Your account has been suspended",
                                        HttpStatus.FORBIDDEN);
                            });
        }

        // Rotation (Kabir B3): burn the presented token and mint a new one, so a leaked refresh token
        // is single-use and a replay after rotation fails.
        stored.revoke();
        refreshTokenRepository.save(stored);
        String newRefreshRaw = jwtService.createRefreshTokenValue();
        refreshTokenRepository.save(
                RefreshToken.create(
                        Ulids.newUlid(),
                        user.getId(),
                        JwtService.hashToken(newRefreshRaw),
                        Instant.now().plusSeconds(jwtService.getRefreshExpirySeconds())));

        String access =
                jwtService.createAccessToken(
                        user.getId(), user.getUserType(), user.getEmail(), workspaceId);
        return new RefreshRotation(access, jwtService.getAccessExpirySeconds(), newRefreshRaw);
    }

    @Transactional
    public void logout(String userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }

    @Transactional
    public String forgotPassword(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(this::createPasswordResetToken);
        return "If this email exists, a reset link has been sent.";
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordPolicy.validate(newPassword);
        String hash = JwtService.hashToken(rawToken);
        PasswordResetToken token =
                passwordResetTokenRepository
                        .findByTokenHashAndUsedFalse(hash)
                        .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_RESET_TOKEN",
                                                "Password reset token is invalid or expired",
                                                HttpStatus.BAD_REQUEST));

        User user =
                userRepository
                        .findById(token.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.BAD_REQUEST));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        token.markUsed();
        userRepository.save(user);
        passwordResetTokenRepository.save(token);
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    /**
     * BR-05 — POST /me/password, the only in-session (already-authenticated) password-change
     * path; distinct from {@link #resetPassword} which is the logged-out forgot-password flow off
     * a mailed token. Re-authenticates on {@code currentPassword} against the stored BCrypt hash
     * before accepting the new one — same {@code passwordEncoder.matches} check {@link
     * #brandLogin}/{@link #creatorLogin} use — and rejects with 401 rather than 400 so a stolen
     * session cookie/access token alone still can't rotate the password without knowing it.
     *
     * <p><b>[SEC: Priya audit, e60d249 follow-up — fixed]</b> On success, revokes every OTHER
     * outstanding refresh token (same "kill leaked/stale sessions on password change" intent as
     * {@link #resetPassword}) but deliberately leaves the CALLER's own current refresh token alone.
     * This used to call {@code revokeAllForUser}, which burns the caller's own session too — the
     * user who just proved they know the new password would then be silently logged out on their
     * very next token refresh, with no error and no explanation (a UI-honesty violation: the
     * response says {@code changed: true} and gives no hint the session is about to die). {@code
     * currentRawRefreshToken} is the raw value read from the caller's own HttpOnly refresh cookie by
     * {@code AccountController} — if present and it resolves to a live token row, that row's id is
     * excluded from the revoke. If it's absent or already invalid/expired (e.g. a non-browser client
     * that never had a refresh cookie), there is nothing to preserve and this falls back to the old
     * revoke-all behavior — correct, just not seamless for that caller.
     */
    @Transactional
    public void changePassword(
            String userId, String currentPassword, String newPassword, String currentRawRefreshToken) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(
                    "INVALID_CURRENT_PASSWORD",
                    "Current password is incorrect",
                    HttpStatus.UNAUTHORIZED);
        }

        PasswordPolicy.validate(newPassword);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        String currentTokenId = currentRefreshTokenId(currentRawRefreshToken);
        if (currentTokenId != null) {
            refreshTokenRepository.revokeAllForUserExcept(user.getId(), currentTokenId);
        } else {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }
    }

    /**
     * Resolves the caller's own live refresh-token row id from the raw cookie value, or {@code
     * null} if there isn't one (absent cookie, unknown hash, already revoked, or expired) — any of
     * which just means {@link #changePassword} has nothing to preserve and falls back to revoking
     * everything.
     */
    private String currentRefreshTokenId(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return null;
        }
        return refreshTokenRepository
                .findByTokenHashAndRevokedFalse(JwtService.hashToken(rawRefreshToken))
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .map(RefreshToken::getId)
                .orElse(null);
    }

    private void createPasswordResetToken(User user) {
        String raw = jwtService.createRefreshTokenValue();
        String hash = JwtService.hashToken(raw);
        String tokenId = Ulids.newUlid();
        passwordResetTokenRepository.save(
                PasswordResetToken.create(tokenId, user.getId(), hash, Instant.now().plusSeconds(3600)));
        if (environment.isDev()) {
            log.info(
                    "[dev] Password reset for {} — POST /auth/reset-password token: {}",
                    user.getEmail(),
                    raw);
        }

        // W3-1 — #17 "password reset" (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). Genuinely open before
        // this change: forgotPassword persisted the token and, in dev only, logged it — there was
        // no publisher for PasswordResetEvent anywhere, so in any non-dev profile the reset email
        // was never actually sent despite the "if this email exists, a reset link has been sent"
        // response claiming otherwise. The raw token is embedded in the link now, not just logged.
        String resetLink = webBaseUrl + "/reset-password?token=" + raw;
        eventPublisher.publishEvent(
                new PasswordResetEvent(user.getId(), null, tokenId, user.getEmail(), resetLink));
    }

    private TokenPair issueTokens(User user, Workspace workspace) {
        String workspaceId = workspace != null ? workspace.getId() : null;
        String access =
                jwtService.createAccessToken(
                        user.getId(), user.getUserType(), user.getEmail(), workspaceId);
        String refreshRaw = jwtService.createRefreshTokenValue();
        refreshTokenRepository.save(
                RefreshToken.create(
                        Ulids.newUlid(),
                        user.getId(),
                        JwtService.hashToken(refreshRaw),
                        Instant.now().plusSeconds(jwtService.getRefreshExpirySeconds())));

        UserDto userDto =
                new UserDto(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getUserType(),
                        user.getStatus(),
                        user.isEmailVerified());

        WorkspaceDto wsDto = null;
        if (workspace != null) {
            wsDto =
                    new WorkspaceDto(
                            workspace.getId(),
                            workspace.getName(),
                            workspace.getSlug(),
                            workspace.getVerificationStatus());
        }

        return new TokenPair(
                userDto,
                wsDto,
                access,
                refreshRaw,
                jwtService.getAccessExpirySeconds(),
                user.isOnboardingCompleted());
    }

    private String uniqueSlug(String companyName) {
        String base = SlugUtils.slugify(companyName);
        String slug = base;
        int attempt = 0;
        while (workspaceRepository.existsBySlug(slug)) {
            attempt++;
            slug = base + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
            if (attempt > 20) {
                slug = base + "-" + Ulids.newUlid().substring(0, 6).toLowerCase();
                break;
            }
        }
        return slug;
    }
}
