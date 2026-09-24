-- V76 -- creator_agent_preferences.phone_model: which mobile phone the creator films on, typed by
-- the creator as free text (e.g. "OPPO Reno 14 Pro", "Redmi Note 13"). Optional; NULL means "not
-- told". Meera uses it to give camera settings that fit that phone (creator context and the Shoot
-- Check frame-check proxy both read it).
--
-- Set only by PUT /creator/agent-preferences/phone, never by the full-replace
-- PUT /creator/agent-preferences, so saving the other settings cannot wipe it. Max length (80) is
-- enforced at the request DTO as well as here.
--
-- Numeric version below the timestamp ones: application.yml sets spring.flyway.out-of-order=true,
-- so environments already past V2026... still apply it.
ALTER TABLE creator_agent_preferences
    ADD COLUMN phone_model VARCHAR(80) NULL;
