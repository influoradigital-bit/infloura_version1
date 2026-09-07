# F-0751 — creator Meera conversations cannot be persisted

**Status:** open, needs a ruling · **Owner of the ruling:** Priya (schema), Swapnil if scope changes
**Found:** 2026-09-07, live on production, after deploying HEAD to Utho
**Blocks:** every creator Meera turn. The panel opens; the session cannot start.

## The failure, verbatim

```
POST /api/v1/creator/meera/sessions → 409 DATA_INTEGRITY_VIOLATION

Cannot add or update a child row: a foreign key constraint fails
(`influora`.`ai_conversations`, CONSTRAINT `fk_conv_workspace`
 FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`))
```

## Why it happens

`MeeraSessionService.createConversationWithOnboardingGreeting` stores a creator's **user id**
in `workspace_id`:

```java
AiConversation.builder()
    .id(Ulids.newUlid())
    .workspaceId(creatorUserId)   // CreatorMeeraController:143 — profile.getUserId()
```

That is deliberate, not a slip. `workspace_id` is being used as a generic tenant key, and
`V74__meera_creator_conversations.sql` says so in its own header:

> "DPDP compliance index over `ai_conversations` for CREATOR-audience Meera turns: list/export/
> delete need a creator-scoped, fast-sortable view **without adding a nullable creator_id to the
> shared (BRAND + CREATOR) `ai_conversations` table**."

So the current design treats `ai_conversations` as shared across both audiences. The obstacle is
`V12__ai_conversations_messages.sql:11`, written in the brand-only era, which still constrains
that column to real workspaces. **The FK contradicts a later, recorded design decision — nobody
removed it.**

## Facts that bound the options

| fact | value | why it matters |
|---|---|---|
| `ai_conversations` rows | **4** | any data migration is trivial |
| `workspace_id` | `varchar(26) NOT NULL` | ULID, same width as a user id |
| creator_profiles / workspaces | **131 / 31** | most creators have no workspace and never will |
| `workspaces.type` | `enum('BRAND','AGENCY')` | no CREATOR member exists |
| joins from `ai_conversations` to `workspaces` | **none found** | nothing reads through the FK |
| `meera_creator_conversations.creator_id` | FK → `creator_profiles(id)` | see the identifier mismatch below |

**Secondary defect, independent of which option is chosen.** `ai_conversations.workspace_id`
holds a **users.id** while `meera_creator_conversations.creator_id` holds a
**creator_profiles.id**. The two creator-side tables key the same conversation on different
identifiers. Whichever option lands should settle on one.

---

## Option A — drop the FK, keep `workspace_id` as a polymorphic tenant key

```sql
ALTER TABLE ai_conversations DROP FOREIGN KEY fk_conv_workspace;
```

**For:** one line; zero data migration; zero code change; matches the code exactly as written;
matches V74's stated design; nothing joins through the FK, so nothing else breaks.

**Against:** brand rows lose referential integrity too — a wrong workspace id would then insert
silently where today it is rejected. The column name becomes a lie: it holds a user id for
creator rows. Nothing in the database distinguishes the two kinds of row.

**Unblocks Meera:** immediately.

## Option B — Option A, plus an explicit discriminator

```sql
ALTER TABLE ai_conversations DROP FOREIGN KEY fk_conv_workspace;
ALTER TABLE ai_conversations
  ADD COLUMN tenant_type ENUM('WORKSPACE','CREATOR') NOT NULL DEFAULT 'WORKSPACE';
UPDATE ai_conversations SET tenant_type = 'WORKSPACE';   -- all 4 existing rows are brand
```

**For:** rows become self-describing; a creator row can never be mistaken for a brand row in a
query, an export, or a DPDP deletion. Cheap while the table holds 4 rows.

**Against:** still no FK on either side, so integrity remains an application-layer promise. Needs
the entity and the creator write path to set `tenant_type`.

**Unblocks Meera:** after a small code change lands with the migration.

## Option C — model it properly: two nullable keys, exactly one set

```sql
ALTER TABLE ai_conversations DROP FOREIGN KEY fk_conv_workspace;
ALTER TABLE ai_conversations MODIFY workspace_id VARCHAR(26) NULL;
ALTER TABLE ai_conversations ADD COLUMN creator_id VARCHAR(26) NULL;
ALTER TABLE ai_conversations
  ADD CONSTRAINT fk_conv_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  ADD CONSTRAINT fk_conv_creator   FOREIGN KEY (creator_id)   REFERENCES creator_profiles(id),
  ADD CONSTRAINT ck_conv_one_tenant CHECK (
        (workspace_id IS NOT NULL AND creator_id IS NULL)
     OR (workspace_id IS NULL AND creator_id IS NOT NULL));
```

**For:** the only option where the database enforces the rule. Both audiences keep referential
integrity, and the exactly-one invariant is checked, not assumed. MySQL 8.4 (this box) enforces
`CHECK`. Also resolves the identifier mismatch — `creator_id` would key on `creator_profiles.id`,
matching `meera_creator_conversations`.

**Against:** contradicts V74's recorded decision to avoid "adding a nullable creator_id to the
shared table" — that reason was about the rollup's indexing needs rather than integrity, but it
is a written decision and reversing it is Priya's call, not a fixer's. Largest code change: the
creator path must write `creator_id` instead of overloading, and
`findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc` needs a creator-keyed sibling.

**Unblocks Meera:** after the code change; the largest of the three but still small at 4 rows.

## Option D — give every creator a workspace row — NOT RECOMMENDED

Would need `workspaces.type` extended to `CREATOR` (today `enum('BRAND','AGENCY')`), 131 backfill
rows, and provisioning at signup.

**Rejected because** every brand-scoped query that reads `workspaces` would begin returning
creators unless each one is filtered by `type` — a cross-tenant leak whose blast radius is the
whole brand surface, introduced to satisfy one FK. It also fills brand-shaped columns
(`billing_email`, `industry`, `company_size`) with nothing. Recorded here so the option is
visibly considered and closed, not silently skipped.

---

## Recommendation

**Option C if this is being fixed once; Option A if Meera needs to work today.**

C is the only version where the database can still refuse a bad row, and it fixes the
users.id-vs-profiles.id split at the same time. Its blocker is not technical — it is that V74
recorded the opposite choice, and that decision should be reversed deliberately.

A is defensible as an interim: it makes the schema agree with code that already shipped, and it
is a single reversible statement. If A is taken, B should follow quickly — a polymorphic key with
no discriminator is the shape that makes a DPDP export or deletion address the wrong rows.

## Do not merge any option without this test

The class of defect that hid this is more important than the defect. Every local build reports
`Tests run: 2673 … Skipped: 15`, and those 15 are the Testcontainers classes —
`DatabaseConstraintIntegrationTest` among them. **No DB-constraint test has ever run on a
developer machine here**, so a schema/code contradiction is invisible until production. Whichever
option lands needs an integration test that starts a Meera session **as a creator** against a
real schema, and that test must actually execute rather than skip.
