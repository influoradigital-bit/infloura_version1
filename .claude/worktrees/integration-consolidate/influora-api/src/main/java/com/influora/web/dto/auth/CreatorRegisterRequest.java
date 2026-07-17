package com.influora.web.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatorRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 50) String firstName,
        @Size(max = 50) String lastName,
        @Size(max = 100) String displayName,
        @AssertTrue(message = "Terms must be accepted") Boolean acceptedTerms) {

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
