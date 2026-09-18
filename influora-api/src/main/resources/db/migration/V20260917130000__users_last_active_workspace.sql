-- The workspace a multi-workspace brand user last chose to work in.
--
-- Login and /auth/refresh stamp the access token with the user's OLDEST active membership. Every
-- brand user is created with their own workspace + OWNER row at signup, so for anyone who later
-- accepts an invite the invited workspace is never the oldest: POST /workspace/members/switch
-- could move them there for one access-token lifetime (15 min), after which the next refresh
-- silently put them back in their own workspace. Accepted invites were therefore unusable.
--
-- NULL = no explicit choice yet -> unchanged oldest-first behaviour. Deliberately no FK: the
-- value is only ever a preference, re-validated against the user's ACTIVE memberships (and the
-- workspace's suspension state) on every read, so a removed membership just falls back.
ALTER TABLE users
  ADD COLUMN last_active_workspace_id VARCHAR(26) NULL;
