-- Gate fix round 2, item 3 (Priya Q8) — working_hours_start/end carried no timezone, so Meera was
-- told "working hours 9-18" with no way to know which 9-18 that was, and reel_floor/story_set_floor
-- /post_floor carried no currency (INR was assumed only in UI labels and a hardcoded assembler
-- prefix). Both are decorative until these columns exist.
ALTER TABLE creator_agent_preferences
    ADD COLUMN working_hours_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    ADD COLUMN floor_currency VARCHAR(3) NOT NULL DEFAULT 'INR';
