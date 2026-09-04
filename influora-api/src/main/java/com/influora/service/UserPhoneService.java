package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.IndianPhoneUtils;
import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * PHONE-0904 — single shared home for the {@code users.phone_number} write path. Extracted out of
 * {@code CreatorProfileService#applyPhone} (PHONE-0829), which had this exact logic private to
 * itself while {@code AuthService#brandRegister} carried an independent copy of the same
 * normalize/validate/duplicate-check rules inline. Three call sites needing this to stay in sync
 * is exactly the "can drift" shape {@link com.influora.common.IndianPhoneUtils}'s own javadoc
 * already warned about for the normalize/isValid pair alone — this class is the same fix applied
 * one layer up, to the {@code ApiException}-throwing write path built on top of it.
 *
 * <p>Callers:
 *
 * <ul>
 *   <li>{@link CreatorProfileService#patchMyProfile} and {@link
 *       CreatorOnboardingService#saveProfile} call {@link #applyPhone(User, String)} directly —
 *       both operate on an already-persisted {@code User} row and want the full
 *       normalize+validate+availability-check+save+TOCTOU-catch flow in one call.
 *   <li>{@link AuthService#brandRegister} cannot use {@link #applyPhone} as-is: it validates
 *       before a {@code User} row even exists, and its persistence is a single combined
 *       {@code save}/{@code catch} covering the user/workspace/wallet insert together (see that
 *       method's own javadoc on FK insert ordering), where a raced duplicate has to be
 *       disambiguated from a raced duplicate email in one catch block. It instead calls the two
 *       building-block methods below ({@link #normalizeAndValidate} and {@link #isTaken}) and
 *       keeps its own save/catch choreography — this still guarantees brandRegister can never
 *       define "valid phone" or "invalid phone" any differently than the other two call sites.
 * </ul>
 */
@Service
public class UserPhoneService {

    private final UserRepository userRepository;

    public UserPhoneService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Normalizes (strips spaces/+91/leading 0 — see {@link IndianPhoneUtils#normalize}) then
     * validates the strict Indian-mobile rule. Returns {@code null} for null/blank input ("no
     * phone supplied", never an error — the convention every caller of this class shares).
     * Throws {@code INVALID_PHONE}/400 for a non-blank value that doesn't normalize to a valid
     * 10-digit Indian mobile number. Does NOT check uniqueness — see {@link #isTaken}.
     */
    public String normalizeAndValidate(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return null;
        }
        String normalized = IndianPhoneUtils.normalize(rawPhone);
        if (!IndianPhoneUtils.isValid(normalized)) {
            throw new ApiException(
                    "INVALID_PHONE",
                    "Enter a valid 10-digit Indian mobile number",
                    HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /** True if some existing user already owns this exact normalized number. */
    public boolean isTaken(String normalizedPhone) {
        return userRepository.existsByPhoneNumber(normalizedPhone);
    }

    /**
     * Full write path for an already-persisted {@code User}. This is the one method on this class
     * whose blank/null handling means "clear the value" — {@link #normalizeAndValidate}'s null
     * return means something unrelated ("no phone supplied" on that call alone). Whether a given
     * call site should even reach this method with a blank value is that call site's decision, not
     * this one's: {@code CreatorProfileService#patchMyProfile} (Settings) treats an explicit blank
     * string as the user's real intent to clear the field and passes it straight through;
     * {@code CreatorOnboardingService#saveProfile} (a create/first-write wizard) guards on
     * {@code !isBlank()} before ever calling this method, precisely so it can never silently erase
     * a number set through another surface (PHONE-0904 D2). Read this paragraph, not the two
     * call sites' comments, as the one authoritative statement of the contract.
     *
     * <p>blank/null {@code rawPhone} clears the stored number ({@link User#setPhoneNumber}
     * full-replace semantics, same "null means no value" convention {@code Workspace#updatePhone}
     * uses). A non-blank value is normalized, validated ({@code INVALID_PHONE}/400), and
     * pre-checked against every OTHER user's number ({@code PHONE_ALREADY_EXISTS}/409) —
     * re-saving a number the same user already owns is intentionally not treated as a duplicate.
     *
     * <p>{@code users.phone_number} is {@code UNIQUE} — the DB constraint is the actual
     * race-safe guarantee, and {@code saveAndFlush} (not plain {@code save}) on the non-blank
     * path below is what makes the catch block able to see a raced violation at all: both callers
     * are {@code @Transactional} with a managed {@code user} entity, so a plain {@code save()}
     * merge emits no SQL and Hibernate defers the INSERT/UPDATE to commit — after this method's
     * catch scope has already exited, letting the violation escape as an uncaught 500 instead of
     * the intended 409. Same rule, same reasoning, as {@code AuthService#brandRegister}'s
     * {@code saveAndFlush} on the {@code UNIQUE(email)} user row (see that method's javadoc,
     * PHONE-0904 D1). The blank/clear branch below stays plain {@code save()} deliberately:
     * writing {@code NULL} into a {@code UNIQUE} column can never violate that constraint (MySQL
     * treats {@code NULL}s as mutually distinct for uniqueness purposes), so there is no race for
     * an early flush to catch.
     */
    public void applyPhone(User user, String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            user.setPhoneNumber(null);
            userRepository.save(user);
            return;
        }

        String normalized = normalizeAndValidate(rawPhone);
        if (!normalized.equals(user.getPhoneNumber()) && isTaken(normalized)) {
            throw new ApiException(
                    "PHONE_ALREADY_EXISTS",
                    "An account with this phone number already exists",
                    HttpStatus.CONFLICT);
        }

        user.setPhoneNumber(normalized);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "PHONE_ALREADY_EXISTS",
                    "An account with this phone number already exists",
                    HttpStatus.CONFLICT);
        }
    }
}
