package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [SEC: Vikram, P4 defensive fix] Unit tests for {@link User#softDelete()} -- previously a second
 * call (double-submit of the delete-account action, a retried request) silently re-stamped {@code
 * deletedAt} with a fresh {@code Instant.now()} on every call, overwriting the original deletion
 * timestamp. Priority: proving a second call preserves the ORIGINAL timestamp.
 */
class UserTest {

    private User newUser() {
        return User.newBrand(
                "01HUSER1234567890ABCD", "brand@example.com", "hash", "Ada", "Lovelace", "Ada L.");
    }

    @Test
    @DisplayName("softDelete: first call blanks PII and stamps deletedAt")
    void firstCallBlanksPiiAndStampsDeletedAt() {
        User user = newUser();

        user.softDelete();

        assertNull(user.getEmail());
        assertNull(user.getFirstName());
        assertNull(user.getLastName());
        assertNull(user.getDisplayName());
        assertNull(user.getAvatarUrl());
        assertNull(user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertNotNull(user.getDeletedAt());
    }

    @Test
    @DisplayName("softDelete: a second call is a no-op -- preserves the ORIGINAL deletedAt, never re-stamps it")
    void secondCallPreservesOriginalDeletedAt() throws InterruptedException {
        User user = newUser();

        user.softDelete();
        Instant originalDeletedAt = user.getDeletedAt();
        Instant originalUpdatedAt = user.getUpdatedAt();

        // Ensure Instant.now() would actually differ if the bug were still present.
        Thread.sleep(5);
        user.softDelete();

        assertEquals(originalDeletedAt, user.getDeletedAt());
        assertEquals(originalUpdatedAt, user.getUpdatedAt());
    }
}
