# QA Review: fix/creator-flow-wiring (C3, C14, C19)

**Date:** 2026-07-24  
**Reviewer:** Kavya  
**Branch:** `fix/creator-flow-wiring`  
**Status:** ✅ **PASS**

---

## Files Reviewed

- `src/lib/api.ts`
- `src/pages/creator-login.tsx`
- `src/pages/creator-chat.tsx`

---

## Deliverable Verification

### ✅ C19: Deliverable Submit Flow

**Requirement:** Chat handler uploads via `api.creatorDeliverables.upload` (multipart, part name `files`) THEN `api.deliverables.submit` with `{finalCaption}` (NOT `fileUrls`). Parent handler does NOT swallow errors or close dialog. Fake hardcoded array replaced with real `liveDeliverables` in live mode. `loadDeliverables` refetches after submit.

**Findings:**

1. **Upload → Submit sequence** (creator-chat.tsx:839-843):
   ```typescript
   await api.creatorDeliverables.upload(data.deliverableId, [data.file], { caption: data.caption });
   await api.deliverables.submit(data.deliverableId, { finalCaption: data.caption });
   ```
   ✅ Correct order. Upload multipart with part name `files`, then submit with `finalCaption`.

2. **Error handling delegation** (creator-chat.tsx:848-849):
   ```typescript
   // Note: do NOT close the dialog or catch here — DeliverableSubmission awaits this
   // handler, closes itself on success, and shows its own destructive toast on failure.
   ```
   ✅ Parent does NOT catch errors. Child component (`DeliverableSubmission`) handles error toast + dialog close (verified at line 124-130 of `deliverable-submission.tsx`).

3. **Live vs. mock data** (creator-chat.tsx:1819-1831):
   ```typescript
   deliverables={
     liveApi
       ? liveDeliverables.map((d) => ({ ... }))
       : [
           { id: 'reel-1', title: 'Instagram Reel #1', ... },
           { id: 'reel-2', title: 'Instagram Reel #2', ... },
         ]
   }
   ```
   ✅ Fake array kept only in non-live branch. Live mode uses real `liveDeliverables`.

4. **Refetch after submit** (creator-chat.tsx:844):
   ```typescript
   await loadDeliverables();
   ```
   ✅ Refetches deliverable list after successful submit.

5. **Backend contract alignment** (api.ts:1676-1682):
   - TS payload: `{ finalCaption?: string; hashtags?: string[]; notes?: string }`
   - Java DTO: `record SubmitRequest(String finalCaption, List<String> hashtags, String notes)`
   - TS response: `{ deliverableId: string; status: DeliverableStatus; message?: string }`
   - Java DTO: `record SubmitResponse(String deliverableId, DeliverableStatus status, String message)`
   ✅ Types match backend DTOs (CreatorDeliverableDtos.java:50,52).

6. **`uploadForm` multipart behavior** (api.ts:3369-3379):
   ```typescript
   const formData = new FormData();
   files.forEach((f) => formData.append('files', f));  // Part name is 'files' (list)
   if (opts.thumbnail) formData.append('thumbnail', opts.thumbnail);
   if (opts.caption) formData.append('caption', opts.caption);
   ...
   return http.uploadForm<CreatorDeliverableUploadResponse>(..., 'creator');
   ```
   ✅ Part name is `files` (not `file`). Backend contract verified (CreatorDeliverableController.java:55 + CreatorDeliverableDtos.java:23-27).

---

### ✅ C14: Counter Proposal Flow

**Requirement:** `handleSubmitCounterForm` calls `api.deals.counter` with fresh `Idempotency-Key` (4th arg `${dealId}-counter-${Date.now()}`), maps `proposedAmount→amount`, folds `terms`/`deadline` into `message`, guards `if (!selectedDeal) return`, catches errors with toast, refreshes via `loadDeals()`. `api.deals.counter` signature accepts optional `idempotencyKey` and threads it to `http.request`.

**Findings:**

1. **Idempotency key** (creator-chat.tsx:808-813):
   ```typescript
   await api.deals.counter(
     selectedDeal.id,
     { amount: data.proposedAmount, message: message || undefined },
     'creator',
     `${selectedDeal.id}-counter-${Date.now()}`,  // Fresh key per submit
   );
   ```
   ✅ Fresh key passed on every submit (prevents same-amount collision, per Kabir).

2. **Field mapping** (creator-chat.tsx:801-810):
   ```typescript
   const message = [
     data.message,
     data.terms && `Terms: ${data.terms}`,
     data.deadline && `Requested deadline: ${data.deadline}`,
   ]
     .filter(Boolean)
     .join('\n\n');
   await api.deals.counter(..., { amount: data.proposedAmount, message: message || undefined }, ...);
   ```
   ✅ `proposedAmount→amount`, `terms`/`deadline` folded into `message` (nothing dropped).

3. **Guard clause** (creator-chat.tsx:795):
   ```typescript
   if (!selectedDeal) return;
   ```
   ✅ Guards against null `selectedDeal`.

4. **Error handling** (creator-chat.tsx:820-826):
   ```typescript
   catch (err) {
     console.error('Failed to submit counter offer', err);
     toast({
       title: 'Could not send counter offer',
       description: err instanceof ApiError ? err.message : 'Please try again.',
       variant: 'destructive',
     });
   }
   ```
   ✅ Catches errors, shows destructive toast. Consistent with rest of file.

5. **Refetch** (creator-chat.tsx:815):
   ```typescript
   await loadDeals();
   ```
   ✅ Refreshes deal list after counter submit.

6. **API signature** (api.ts:1207-1221):
   ```typescript
   counter: (
     id: string,
     payload: { amount: number; message?: string; deliverables?: Array<...> },
     role: Role = 'creator',
     idempotencyKey?: string,  // New optional 4th param
   ) =>
     isLive()
       ? http.request<Deal>('POST', `/deals/${id}/counter`, { role, body: payload, idempotencyKey })
       : mockOr<{ id: string }>({ id }),
   ```
   ✅ Signature accepts optional `idempotencyKey`, threads it to `http.request` (line 343: `'Idempotency-Key': idempotencyKey`).

7. **No breaking changes to other callers** (brand-campaign-detail.tsx:689):
   ```typescript
   await api.deals.counter(selectedBid.id, { amount, message: counterMessage || undefined }, 'brand');
   ```
   ✅ Brand-side call still works (4th param optional).

---

### ✅ C3: Forgot Password Link

**Requirement:** `creator-login.tsx` forgot-password is now a `<Link to="/creator/forgot-password">` (Link already imported).

**Findings:**

1. **Link component** (creator-login.tsx:124-128):
   ```typescript
   <Link
     to="/creator/forgot-password"
     className="text-sm text-primary hover:underline transition-colors"
   >
     Forgot password?
   </Link>
   ```
   ✅ Changed from `<button type="button">` to `<Link>`. Link was already imported (no new import needed).

---

### ✅ api.ts Hygiene

**Requirement:** `http.upload` refactored to delegate to new `uploadForm` (behavior unchanged: still part name `file`); `deliverables.submit` no other callers broke (grep). New `CreatorDeliverableUploadResponse` type is sound.

**Findings:**

1. **`http.upload` refactor** (api.ts:428-432):
   ```typescript
   async upload<T>(path: string, file: File, role: Role = 'brand'): Promise<T> {
     const formData = new FormData();
     formData.append('file', file);  // Part name still 'file'
     return this.uploadForm<T>(path, formData, role);
   }
   ```
   ✅ Delegates to `uploadForm`, behavior unchanged (part name still `file`).

2. **`uploadForm` implementation** (api.ts:440-458):
   ```typescript
   async uploadForm<T>(path: string, formData: FormData, role: Role = 'brand'): Promise<T> {
     const token = this.getToken(role);
     const res = await this.fetchWithAuthRetry(
       `${API_BASE_URL}${path}`,
       { method: 'POST', body: formData, credentials: 'include', headers: token ? { Authorization: `Bearer ${token}` } : undefined },
       role,
       !!token,
     );
     const envelope = (await res.json()) as ApiEnvelope<T>;
     if (!res.ok || !envelope.success) {
       throw new ApiError(envelope.error?.code || 'UPLOAD_FAILED', envelope.error?.message || 'Upload failed');
     }
     return envelope.data as T;
   }
   ```
   ✅ Clean implementation. No `Content-Type` header (browser sets multipart boundary).

3. **Other `deliverables.submit` callers**:
   - Grep results: only `creator-chat.tsx:843` calls it.
   ✅ No breaking changes.

4. **`CreatorDeliverableUploadResponse` type** (api.ts:3323-3338):
   ```typescript
   export interface CreatorDeliverableUploadFile {
     id: string;
     fileType: string;
     fileName: string;
     url: string;
     thumbnailUrl: string | null;
     fileSize: number | null;
     durationSeconds: number | null;
   }
   export interface CreatorDeliverableUploadResponse {
     versionId: string;
     versionNumber: number;
     files: CreatorDeliverableUploadFile[];
     status: CreatorDeliverableRowStatus;
   }
   ```
   - Java DTOs:
     - `record DeliverableFileResponse(String id, String fileType, String fileName, String url, String thumbnailUrl, Long fileSize, Integer durationSeconds)` (CreatorDeliverableDtos.java:14-21)
     - `record UploadResponse(String versionId, int versionNumber, List<DeliverableFileResponse> files, DeliverableStatus status)` (CreatorDeliverableDtos.java:23-27)
   ✅ Types match backend DTOs.

---

## React Hook Dependency Correctness

**`loadDeliverables` useCallback** (creator-chat.tsx:690-701):
```typescript
const loadDeliverables = React.useCallback(async () => {
  if (!liveApi || !selectedDeal) {
    setLiveDeliverables([]);
    return;
  }
  try {
    setLiveDeliverables(await api.creatorDeliverables.listForDeal(selectedDeal.id));
  } catch (err) {
    console.error('Failed to load deliverables', err);
    setLiveDeliverables([]);
  }
}, [liveApi, selectedDeal?.id]);
```
✅ Deps are `[liveApi, selectedDeal?.id]`. Correct (uses `selectedDeal.id` inside, but guards `if (!selectedDeal)` first).

**`selectedDeal` definition** (creator-chat.tsx:583-586):
```typescript
const selectedDeal = React.useMemo(
  () => dealRooms.find((d) => d.id === selectedDealId) ?? dealRooms[0] ?? null,
  [dealRooms, selectedDealId],
);
```
✅ Safe nullability. `loadDeliverables` guards `if (!selectedDeal)`.

---

## Code Quality Checklist

### TypeScript/Code Standards
- ✅ No `any` types in the diff
- ✅ All props properly typed (new interfaces: `CreatorDeliverableUploadFile`, `CreatorDeliverableUploadResponse`, `CreatorApplicationRow`)
- ✅ No unused variables or imports
- ✅ No `console.log` in production code (only `console.error` in catch blocks)

### Security
- ✅ No API keys hardcoded
- ✅ No sensitive data in code
- ✅ Idempotency-Key generated client-side (not hardcoded)

### Performance
- ✅ No performance anti-patterns

### Error Handling
- ✅ Consistent error handling (toast on `ApiError`, fallback message)
- ✅ Error boundaries respected (child component owns dialog close)

### Mock Mode Compatibility
- ✅ Mock-mode path still works (hardcoded deliverable array kept in non-live branch)
- ✅ All new API calls guard `if (liveApi)` or use `isLive()` in api.ts

---

## Summary

All three deliverables verified:

1. **C19 (deliverable submit)** — Two-step upload→submit flow correct, error handling delegated to child, live data wired, refetch works.
2. **C14 (counter proposal)** — Idempotency key fresh per submit, field mapping correct, error handling consistent, refetch works.
3. **C3 (forgot password)** — Link component wired correctly.

**No issues found.** Code is clean, types match backend DTOs, React hooks deps correct, mock mode preserved, no breaking changes to other callers.

---

## Next Steps

✅ **PASS** — route to Meera for local verification (build + runtime checks).
