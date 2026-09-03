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
 * <p>Added as the 7th canonical component with a secondary 6-arg constructor delegating {@code
 * inviteToken = null}, so every pre-existing call site (tests included) that built this record
 * with the original 6 fields keeps compiling unchanged — this DTO's file scope was extended
 * beyond pkg-q55-invite-token's original file list specifically to avoid widening {@code
 * AuthController#creatorRegister}'s Java parameter list, which would have broken {@code
 * AuthControllerTest} (a file outside this work package's edit scope).
 */
public record CreatorRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 50) String firstName,
        @Size(max = 50) String lastName,
        @Size(max = 100) String displayName,
        @AssertTrue(message = "Terms must be accepted") Boolean acceptedTerms,
        @Size(max = 1024) String inviteToken) {

    public CreatorRegisterRequest(
            String email,
            String password,
            String firstName,
            String lastName,
            String displayName,
            Boolean acceptedTerms) {
        this(email, password, firstName, lastName, displayName, acceptedTerms, null);
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
