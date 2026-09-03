-- Gate fix round 1 (Priya Q7, T-MEERA-CREATOR-PHASE-A). influora-ai's spend_tracker.py already
-- reads a per-creator monthly AI-spend cap override off the CREATOR context payload's
-- `ai_monthly_cap_usd` key (see spend_tracker.py:99/484 -- "set by support through the admin
-- override") but nothing in influora-api ever wrote that key: a creator who hit the default
-- USD 0.75/month cap had no lever, and no admin surface existed to raise it. NULL means "use the
-- process-wide default (AI_CREATOR_MONTHLY_CAP_USD)" -- unchanged behavior for every existing row.
ALTER TABLE creator_agent_preferences
    ADD COLUMN ai_monthly_cap_usd DECIMAL(6, 2) NULL;
