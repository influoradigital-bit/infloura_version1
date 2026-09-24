package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V76 -- {@link CreatorAgentPreferences#updatePhoneModel}: the phone the creator films on is
 * creator-typed free text, stored trimmed, and a blank value clears it rather than saving spaces
 * Meera would then be told about.
 */
class CreatorAgentPreferencesPhoneModelTest {

    private static CreatorAgentPreferences prefs() {
        return CreatorAgentPreferences.newWithDefaults("prefs-1", "profile-1", null, null, null, null);
    }

    @Test
    @DisplayName("a new row has no phone model")
    void newRowHasNoPhone() {
        assertNull(prefs().getPhoneModel());
    }

    @Test
    @DisplayName("the typed value is stored trimmed")
    void storesTrimmedValue() {
        CreatorAgentPreferences p = prefs();

        p.updatePhoneModel("  OPPO Reno 14 Pro \t");

        assertEquals("OPPO Reno 14 Pro", p.getPhoneModel());
    }

    @Test
    @DisplayName("blank or null clears a saved phone to null")
    void blankOrNullClears() {
        CreatorAgentPreferences p = prefs();
        p.updatePhoneModel("Redmi Note 13");

        p.updatePhoneModel("   ");
        assertNull(p.getPhoneModel());

        p.updatePhoneModel("Redmi Note 13");
        p.updatePhoneModel(null);
        assertNull(p.getPhoneModel());
    }

    @Test
    @DisplayName("the full-replace applyPreferences never touches the saved phone")
    void applyPreferencesLeavesPhoneAlone() {
        CreatorAgentPreferences p = prefs();
        p.updatePhoneModel("Redmi Note 13");

        p.applyPreferences(
                new BigDecimal("1000"), null, null, "INR", "[]", "[]", 1, "en-IN", "FORMAL",
                null, null, null, "[]", null, false, null);

        assertEquals("Redmi Note 13", p.getPhoneModel());
    }
}
