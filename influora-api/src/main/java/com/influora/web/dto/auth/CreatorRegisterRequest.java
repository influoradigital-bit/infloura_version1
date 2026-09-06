package com.influora.web.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — {@code inviteToken} is the signed, single-use token
 * {@code creator-register.tsx} reads out of the {@code invite_token} query param on the
 * {@code signup_url} (see {@code InviteTokenService}, {@code AdminCreatorConnectionService
 * #sendJoinInvitationEmail}) and forwards on the register call. Nullable: an ordinary (non-invite)
 * registration never sets it, and {@code AuthService#creatorRegister} treats a missing/blank value
 * as "no invite to consume" rather than an error.
 *
 * <p>PHONE-0906 (Swapnil, superseding the creator half of {@code
 * wiki/decisions/phone-0904-cto-review.md} — see that file's addendum) — {@code phone} is REQUIRED
 * for creator registration, mirroring {@link BrandRegisterRequest}. Creator phone capture is NO
 * LONGER optional at signup; the still-optional field on creator onboarding step 2 and in creator
 * Settings is now an EDIT of the number captured here, never the first capture. Deliberately not
 * enforced with {@code @NotBlank}, for the identical reason spelled out on {@code
 * BrandRegisterRequest}: a missing value needs its own machine-readable {@code PHONE_REQUIRED}/400
 * from {@code AuthService#creatorRegister}, kept distinct from {@code INVALID_PHONE}/400 (present
 * but malformed) and {@code PHONE_ALREADY_EXISTS}/409 (duplicate), so the client can branch on the
 * code and put the right inline message on the field instead of a generic Bean-Validation
 * field-errors body.
 *
 * <p><b>Known dead-end, accepted at ruling time:</b> {@code users.phone_number} is {@code UNIQUE}
 * across ALL user types and has no SMS verification and no support/admin release path. A creator
 * whose number already sits on a brand (or another creator) account now gets a 409 they cannot
 * themselves resolve — where before this change they simply left the field blank. That is the
 * exact risk {@code phone-0904-cto-review.md} D4 records; it is unchanged and still unfunded.
 *
 * <p>When present, normalized/validated server-side against the same strict Indian-mobile rule as
 * every other phone-capture call site (shared via {@code UserPhoneService}, built on {@code
 * com.influora.common.IndianPhoneUtils}) — never trusting the client's own regex. Persisted to
 * {@code users.phone_number}, the same column brand signup, creator onboarding and creator
 * Settings all write.
 */
public record CreatorRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 50) String firstName,
        @Size(max = 50) String lastName,
        @Size(max = 100) String displayName,
        @AssertTrue(message = "Terms must be accepted") Boolean acceptedTerms,
        @Size(max = 1024) String inviteToken,
        @Size(max = 20) String phone) {

    /**
     * 7-arg form (no phone) — kept so every call site written against the pre-PHONE-0906 canonical
     * constructor keeps compiling. Delegates {@code phone = null}, which {@code
     * AuthService#creatorRegister} now rejects with {@code PHONE_REQUIRED}: this constructor is a
     * source-compatibility shim, NOT a "phone is optional" affordance.
     */
    public CreatorRegisterRequest(
            String email,
            String password,
            String firstName,
            String lastName,
            String displayName,
            Boolean acceptedTerms,
            String inviteToken) {
        this(email, password, firstName, lastName, displayName, acceptedTerms, inviteToken, null);
    }

    /**
     * Original 6-arg form (no invite token, no phone) — same compatibility rationale as the 7-arg
     * overload above, preserved from Q5.5.
     */
    public CreatorRegisterRequest(
            String email,
            String password,
            String firstName,
            String lastName,
            String displayName,
            Boolean acceptedTerms) {
        this(email, password, firstName, lastName, displayName, acceptedTerms, null, null);
    }

    @AssertTrue(message = "Provide displayName or both firstName and lastName")
    private boolean isNameValid() {
        boolean hasDisplayName = displayName != null && !displayName.isBlank();
        boolean hasFirstLast =
                firstName != null
                        && !firstName.isBlank()
                        && lastName != null
                        && !lastName.isBlank();
        return hasDisplayName || hasFirstLast;
    }
}
