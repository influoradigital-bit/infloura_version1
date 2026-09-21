# T-MEERASNAP-0920 — Backend findings: brand-profile DTO for StageSnapshot

Read-only investigation. No files edited, no build run.

## 1. Controller + DTO serving GET /meera/brand-profile

`MeeraController.brandProfile()` — influora-api/src/main/java/com/influora/web/MeeraController.java:199-220.
Returns `ApiResponse<BrandProfileResponse>`. Record: influora-api/src/main/java/com/influora/web/dto/meera/MeeraDtos.java:82-89

```java
public record BrandProfileResponse(
        String workspaceId,
        String websiteUrl,
        String analysisStatus,
        List<String> nicheTags,
        Object productCatalog,
        String analysisError) {}
```

Matches the browser DTO cited in the brief: `MeeraBrandProfile` (src/lib/meera-api.ts:109).

## 2. Display name

NOT available on this path today. `BrandProfileResponse` has no name field, and `BrandProfile` entity (influora-api/src/main/java/com/influora/domain/entity/BrandProfile.java) has no name column either — its table `brand_profiles` (V11__brand_profiles.sql) holds only analysis data, not identity.

The real source is `Workspace.name` — influora-api/src/main/java/com/influora/domain/entity/Workspace.java:26 (`getName()` at :166), table `workspaces`, column `name`. `MeeraController.brandProfile()` already loads the `Workspace` row (`var workspace = brandContextService.requireBrandWorkspace(principal);`, line 202) but only reads `workspace.getId()` off it — `getName()` is sitting right there unused.

## 3. brand_color — CONTEXT bullet is WRONG about a mismatch

Stored key: `AnalyzeSiteTriggerService.toCallback()` (:229-239) always writes `brand_aesthetic` as `{"accent_color": <hex>}` (:234) — never a top-level `brand_color` column. Confirmed nested key, matches the brief.

But the two readers do NOT disagree. `BrandContextAssembler.extractBrandColor()` (influora-api/src/main/java/com/influora/service/meera/BrandContextAssembler.java:170-177) reads `map.get("accent_color")` from the nested `brand_aesthetic` blob — not `brand_color` directly, contrary to what the brief's bullet 3 implied. The javadoc right above it (:163-168) spells this out explicitly: `brand_color` is a *synthesized top-level field name in the outbound `ContextResponse`/`MeeraContextDtos`*, produced by extracting `accent_color` — it is not read back from a `brand_color` key anywhere. Test `BrandContextAssemblerTest.java:133-140` (`"brand_color is extracted from brand_aesthetic.accent_color"`) confirms this is the intended, working contract, not a bug.

So: retrievable, single canonical key (`accent_color` nested in `brand_aesthetic`), one working reader (`BrandContextAssembler`), zero readers that actually key off a top-level `brand_color`. No mismatch exists today. The risk is only that `MeeraController.brandProfile()` (browser path) doesn't call this extraction logic at all yet — see §5.

## 4. product_catalog stored shape

Confirmed by reading the actual writer, `influora-ai/app/routes/analyze_site.py` (`merge_known_products`, :107-135, and `known_known_to_dict`-style scraped-fact block :97-103), plus the Spring-side normalizer `AnalyzeSiteTriggerService.normalizePriceSource()` (:250-267). Every persisted catalog entry is exactly:

```json
{"name": "<string, ≤200 chars>", "price": <number|null>, "currency": "<string|null>", "price_source": "scraped"|"inferred"}
```

No `image`/`imageEmoji`/`thumbnail` field exists anywhere in the pipeline — grepped both `influora-api` and `influora-ai` for `image`/`thumbnail` near product/catalog code, zero hits. `AnalyzeSiteAiDtos.Data.productCatalog()` (:44-49) is typed `List<Map<String,Object>>` (untyped bag), and `MeeraController.brandProfile()` passes the raw JSON straight through as `Object` (MeeraDtos.java:217, `JsonLists.objectFromJson(...)`) — no allow-list filtering happens on the browser path today (the `name/price/currency/price_source` allow-list at BrandContextAssembler.java:115-116 only applies to the AI-context path, not the browser DTO).

So: yes to a price (`price`+`currency`+`price_source`), no to an image — the mock's `imageEmoji` has no real backing data and would need a client-side placeholder/emoji-by-category mapping, not a new server field.

## 5. Scope to add brandName + brandColor + typed product list to the browser DTO

Read-side only — no DB migration. All three values already exist in storage (`workspaces.name`, `brand_profiles.brand_aesthetic.accent_color`, `brand_profiles.product_catalog`).

Files to change:
- `influora-api/src/main/java/com/influora/web/dto/meera/MeeraDtos.java:82-89` — extend `BrandProfileResponse` with `brandName`, `brandColor`, and replace `Object productCatalog` with a typed record, e.g. `List<ProductCatalogItem>` where `ProductCatalogItem(String name, BigDecimal price, String currency, String priceSource)`.
- `influora-api/src/main/java/com/influora/web/MeeraController.java:199-220` — populate `workspace.getName()` (already in scope, unused today) and extract `accent_color` from `profile.getBrandAestheticJson()`. `extractBrandColor()` is currently `private` on `BrandContextAssembler` (:170) — either duplicate the ~6-line extraction or pull it into a small shared static utility both classes call, to avoid the same key drifting apart a second time.
- Mapping `productCatalogJson` → the new typed record needs a small parse helper (reuse `JsonLists` conventions already used at MeeraDtos.java:217) instead of the current raw `Object` passthrough.
- `src/lib/meera-api.ts:109` — extend `MeeraBrandProfile` with `brandName: string | null`, `brandColor: string | null`, and a typed `productCatalog` interface matching the new shape. (Frontend consumption in `StageSnapshot.tsx` is Ananya's, not mine.)
- API doc update: `wiki/processes/api-docs.md` (I own this per my role) once the shape is agreed with Ananya.

No entity/migration change needed anywhere — `Workspace`, `BrandProfile` already carry every field required.
