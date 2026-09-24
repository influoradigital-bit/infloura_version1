package com.influora.web.dto.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.enums.ChargeKind;
import com.influora.web.dto.meera.MeeraDtos.SendTurnRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** 2026-09-22 — the button action decides the charge, and wins over the voice toggle. */
class SendTurnRequestActionTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void plainMessageIsATurnAndVoiceIsAVoiceTurn() {
        assertEquals(ChargeKind.TURN, new SendTurnRequest("hi", null).creatorChargeKind());
        assertEquals(ChargeKind.VOICE_TURN, new SendTurnRequest("hi", true).creatorChargeKind());
    }

    @Test
    void buttonActionsMapToTheirChargeAndBeatVoice() {
        assertEquals(ChargeKind.SCRIPT, new SendTurnRequest("x", false, "SCRIPT").creatorChargeKind());
        assertEquals(ChargeKind.SCRIPT, new SendTurnRequest("x", true, "SCRIPT").creatorChargeKind());
        assertEquals(ChargeKind.PROFILE_REVIEW, new SendTurnRequest("x", true, "PROFILE_REVIEW").creatorChargeKind());
    }

    @Test
    void unknownActionIsRejectedNotSilentlyFree() {
        assertFalse(validator.validate(new SendTurnRequest("x", false, "FREE")).isEmpty());
        assertFalse(validator.validate(new SendTurnRequest("x", false, "script")).isEmpty());
        assertTrue(validator.validate(new SendTurnRequest("x", false, "SCRIPT")).isEmpty());
        assertTrue(validator.validate(new SendTurnRequest("x", false, null)).isEmpty());
    }
}
