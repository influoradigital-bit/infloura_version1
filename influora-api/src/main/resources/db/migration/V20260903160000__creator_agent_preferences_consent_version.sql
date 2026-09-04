-- Gate fix round 2, item 1 (Priya Q3) — consent was recorded with a timestamp but no version, so
-- a change to the DPDP notice text could never force re-consent from an already-consented
-- creator. Existing rows default to 'v1' (the version every already-shipped consent screen has
-- shown) so they are grandfathered in as consented under v1, not silently un-consented by this
-- migration.
ALTER TABLE creator_agent_preferences
    ADD COLUMN consent_version VARCHAR(16) NOT NULL DEFAULT 'v1';
