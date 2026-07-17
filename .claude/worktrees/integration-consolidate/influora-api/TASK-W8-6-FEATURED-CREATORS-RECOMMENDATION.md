# W8-6: featured_creators — Recommendation

## Current State (grep-confirmed 2026-07-15)

**Table:** `featured_creators` (V6__creators_collaborations.sql, line 198-208)
**Entity:** `FeaturedCreator.java` — fully mapped, all columns, getters present
**Repository:** `FeaturedCreatorRepository.java` — Spring Data, custom query `findActiveFeatured`
**Consumer:** `CreatorDiscoveryService.getFeatured()` (lines 274-312)

The consumer reads `featured_creators` and serves it via `GET /discovery/featured/:category?`. When the table is empty, it falls back to algorithmic sections (rising_star, editors_pick, top_fitness, top_creators) built from `CreatorProfile` queries. This is EXCELLENT design — the table acts as an override, not a hard dependency.

## The "Orphaned" Claim

Priya's finding: "discovery READS it but nothing ever WRITES."

This is TRUE. Grep confirms zero write paths:
- No controller exposes a featured-creator admin endpoint.
- No service writes to `FeaturedCreator`.
- No migration seeds data.
- The table schema exists and is mapped, but it has never been populated in production.

**BUT** this is NOT "orphaned." The entity is LIVE and consumed. The fallback is intentional, not accidental. The only gap is the admin write surface.

## Two Options

### Option (a): Build Minimal Admin Write Path [RECOMMENDED]

**Why:** The infrastructure is sound. Adding a thin admin endpoint is < 100 LOC and delivers immediate value — platform admins can curate featured sections instead of relying solely on algorithmic ranking.

**What to add:**
1. **DTO** (in `AdminCreatorDtos.java`):
   ```java
   public record SetFeaturedRequest(
       @NotBlank String creatorProfileId,
       @NotBlank String featuredCategory,
       int displayOrder,
       Instant featuredFrom,
       Instant featuredUntil,
       String featuredReason) {}
   ```

2. **Service method** (in `AdminCreatorService.java`):
   ```java
   @Transactional
   public void setFeatured(AuthPrincipal principal, SetFeaturedRequest req) {
       adminContext.requireRole(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
       CreatorProfile profile = creatorProfileRepository.findById(req.creatorProfileId())
           .orElseThrow(() -> new ApiException(...));
       FeaturedCreator featured = FeaturedCreator.builder()
           .id(Ulids.newUlid())
           .creatorProfileId(profile.getId())
           .featuredCategory(req.featuredCategory())
           .displayOrder(req.displayOrder())
           .featuredFrom(req.featuredFrom() != null ? req.featuredFrom() : Instant.now())
           .featuredUntil(req.featuredUntil())
           .active(true)
           .featuredByUserId(principal.getUserId())
           .featuredReason(req.featuredReason())
           .createdAt(Instant.now())
           .updatedAt(Instant.now())
           .build();
       featuredCreatorRepository.save(featured);
       adminAuditLog.record(principal, "FEATURED_CREATOR", "SET", featured.getId(), ...);
   }
   ```

3. **Controller endpoint** (in `AdminCreatorController.java`):
   ```java
   @PostMapping("/admin/creators/{creatorId}/featured")
   public ResponseEntity<Void> setFeatured(
       @AuthPrincipal AuthPrincipal principal,
       @PathVariable String creatorId,
       @Valid @RequestBody SetFeaturedRequest req) {
       adminCreatorService.setFeatured(principal, req);
       return ResponseEntity.ok().build();
   }
   ```

4. **Tests:** Mockito unit test verifying SUPER_ADMIN/ADMIN can set featured, SUPPORT cannot.

**Effort:** 1-2 hours (Vikram). Fits existing admin surface exactly (same RBAC, same audit pattern as `AdminCreatorService.suspend/reinstate`).

**Value:** Immediate. Admins can now curate "Editor's Picks" with real entries, not just algorithmic fallback.

### Option (b): Drop the Table [NOT RECOMMENDED]

**Why NOT:** The fallback is algorithmic TODAY, but curated featured sections are a standard marketplace feature (Upwork, Fiverr, Instagram Explore all have them). Dropping the table trades 30 minutes of "cleanup" for rebuilding the exact same schema + entity + repository later when product needs it.

**Cost if dropped:** Re-migration (V99_add_back_featured_creators.sql), re-entity creation, re-wiring the repository query, re-testing `CreatorDiscoveryService.getFeatured()` (which already handles both paths correctly).

**If you drop it anyway:** Must also remove `FeaturedCreator.java`, `FeaturedCreatorRepository.java`, and the fallback logic in `CreatorDiscoveryService.getFeatured()` (lines 279-284, 287-310) — otherwise you have a live entity with no backing table, which is a worse state than the current "entity with no write path."

## Recommendation

**Build option (a).** The entity is not orphaned; it's a designed feature waiting for its admin write surface. The effort is trivial and aligns with the existing admin CRUD pattern. Deleting it now would be premature optimization.

## Decision Authority

This is a **product decision** (does Influora want curated featured sections?), not a pure code-cleanup question. Escalate to **Swapnil** (CEO) or **Tejas** (CMO) if you need product input. From a pure engineering perspective, the table is valid and the write path is a straightforward addition.
