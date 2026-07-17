package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.SlugUtils;
import com.influora.common.Ulids;
import com.influora.domain.entity.PasswordResetToken;
import com.influora.domain.entity.RefreshToken;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.PasswordResetTokenRepository;
import com.influora.repository.RefreshTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.JwtService;
import com.influora.web.dto.auth.AuthDtos.TokenPair;
import com.influora.web.dto.auth.AuthDtos.UserDto;
import com.influora.web.dto.auth.AuthDtos.WorkspaceDto;
import com.influora.web.dto.auth.BrandRegisterRequest;
import com.influora.web.dto.auth.LoginRequest;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final JwtService jwtService;
    private final BrandEmailOtpService brandEmailOtpService;

    @Value("${influora.auth.require-email-verification:true}")
    private boolean requireEmailVerification;

    @Value("${influora.auth.require-email-otp-before-register:false}")
    private boolean requireEmailOtpBeforeRegister;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            WalletRepository walletRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            BrandEmailOtpService brandEmailOtpService) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.brandEmailOtpService = brandEmailOtpService;
    }

    @Transactional
    public TokenPair brandRegister(BrandRegisterRequest req) {
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException("EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
        }

        if (requireEmailOtpBeforeRegister) {
            brandEmailOtpService.requireVerifiedEmail(req.email());
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
        user.setEmailVerified(true);

        String workspaceId = Ulids.newUlid();
        String slug = uniqueSlug(req.companyName());
        Workspace workspace =
                Workspace.newBrand(workspaceId, req.companyName(), slug, req.industry(), req.companySize());

        workspaceMemberRepository.save(WorkspaceMember.owner(Ulids.newUlid(), workspaceId, userId));
        walletRepository.save(Wallet.forWorkspace(Ulids.newUlid(), workspaceId));
        userRepository.save(user);
        workspaceRepository.save(workspace);

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

        WorkspaceMember member =
                workspaceMemberRepository
                        .findFirstByUserIdAndActiveTrue(user.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND",
                                                "No workspace found for this user",
                                                HttpStatus.NOT_FOUND));

        Workspace workspace =
                workspaceRepository
                        .findById(member.getWorkspaceId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND",
                                                "Workspace not found",
                                                HttpStatus.NOT_FOUND));

        user.markLogin();
        userRepository.save(user);
        return issueTokens(user, workspace);
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

        WorkspaceMember member =
                workspaceMemberRepository.findFirstByUserIdAndActiveTrue(user.getId()).orElse(null);
        String workspaceId = member != null ? member.getWorkspaceId() : null;

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

    private void createPasswordResetToken(User user) {
        String raw = jwtService.createRefreshTokenValue();
        String hash = JwtService.hashToken(raw);
        passwordResetTokenRepository.save(
                PasswordResetToken.create(
                        Ulids.newUlid(),
                        user.getId(),
                        hash,
                        Instant.now().plusSeconds(3600)));
        log.info("[dev] Password reset for {} — POST /auth/reset-password token: {}", user.getEmail(), raw);
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
