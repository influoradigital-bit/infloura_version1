# CR-38 / H-2 — IPv6 topology check on the live box

> **Ran:** 2026-07-28, against `http://200.141.1.6` / `srv1844961.hstgr.cloud` (VPS 1844961).
> **By:** Claude, at the repo owner's instruction. Read-only probing + Hostinger VPS metadata.
> **Answers:** Kabir's H-2 in `CR-11-xff-fix-rereview.md` — *"`native` is correct only if Caddy
> sees a non-RFC1918 peer; nobody has checked; `dig AAAA`, then exhaust the bucket from two IPs."*

## Headline

**The precondition is REAL. The definitive bypass test could NOT be run, because the box serves
Wave 2 — neither the CR-38 fix nor the build Kabir code-reviewed.** Deploying the fix is a
prerequisite for the actual test. What is settled is that H-2 is **not moot on this box**, which is
the thing Kabir said nobody had checked.

## What is confirmed (durable, independent of any deploy)

1. **The VPS has a real, routable IPv6 address.** Hostinger metadata (`getVirtualMachineDetailsV1`,
   VPS 1844961): `ipv6: [{ address: "2a02:4780:63:a04e::1", ptr: "srv1844961.hstgr.cloud" }]`.
   Not link-local, not absent — a public /128 with a PTR.
2. **DNS publishes it.** `srv1844961.hstgr.cloud` has both an `A` (`200.141.1.6`) and an `AAAA`
   (`2a02:4780:63:a04e::1`). Kabir's own escape hatch — *"moot if there are no `AAAA` records"* —
   therefore **does not apply**.
3. **The app answers over IPv6.** `curl -6 http://srv1844961.hstgr.cloud/` → `HTTP 200` via
   `2a02:4780:63:a04e::1`. So inbound IPv6 reaches Caddy and is proxied to the API. The whole
   premise of H-2's IPv6 branch is live infrastructure here, not a hypothetical.

**Conclusion for whoever deploys:** treat CR-38's IPv6 bypass as a live risk to verify, **not**
something to wave off. The condition Kabir flagged — v6 present — holds.

## What could NOT be tested, and why

The behavioural test (exhaust the rate-limit bucket from two IPs, confirm they don't share a budget)
requires the **fixed** build. The box serves **Wave 2**:

- Served bundle is `index-B_x5CUtn.js` (the recorded Wave 2 hash).
- `POST /api/v1/client-errors` → **401**, not a handled 202 — the CR-11 endpoint (Wave 6) does not
  exist on this backend; the security chain rejects an unmapped path.

So the `AuthRateLimitFilter` running on the box is an **older version than the one Kabir reviewed**,
and the `forward-headers-strategy` in effect is whatever Wave 2 shipped — not the `native` from
`dd9645a`. Any bucket behaviour measured now is an artifact of old code and says nothing about
whether the fix closes or reopens the v6 path.

## Observed on the Wave-2 build anyway — recorded, NOT evidence about the fix

Probed `POST /api/v1/auth/refresh` (harmless: no cookie → 401, but the rate-limit filter runs first
and exposes `X-RateLimit-Remaining`). Caveat up front: the window is 60s and requests were seconds
apart, so exact counts drift as the window rolls.

- **A rotated/spoofed `X-Forwarded-For` did NOT mint a fresh bucket.** Every IPv4 request decremented
  the same counter whether the header was absent, `203.0.113.77`, or `198.51.100.9, 10.0.0.5`. This
  is the *opposite* of the naive CR-38 spoofing attack — on the deployed Wave-2 build, that specific
  attack does not work.
- **IPv4 and IPv6 clients keyed to SEPARATE buckets.** A first v6 request got a fresh count while the
  v4 bucket was mid-decrement.

Do not read either line as "CR-38 is fine on the box." It is old code; the point of CR-38 is what the
*fixed* build does, and that is untested until it deploys.

## The actual test to run, AFTER `native` is deployed

Kabir's procedure, made concrete for this box. Run from **two genuinely different external IPs**
(v4 is fine; the sharper check is one v4 + one v6, since the v6 path is the suspected bypass):

```bash
# 1. Confirm the fix is actually deployed (env, not image default — see §9 / CR-38 row):
#    the box's compose must carry SERVER_FORWARD_HEADERS_STRATEGY=native.
#    A frontend bundle hash proves NOTHING here.

# 2. From external IP A, exhaust the sensitive bucket (limit 10):
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code} rem=%{header_x-ratelimit-remaining}\n" \
    -X POST http://200.141.1.6/api/v1/auth/brand/login \
    -H 'Content-Type: application/json' --data '{"email":"x@x.com","password":"x"}'
done
# Expect the 11th to be 429.

# 3. THE BYPASS CHECK — from A, now spoof X-Forwarded-For per request:
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H 'X-Forwarded-For: 9.9.9.9' http://200.141.1.6/api/v1/auth/brand/login ...
# PASS = still 429 (spoof ignored). FAIL = 200 (fresh bucket → CR-38 not closed).

# 4. THE IPv6 CHECK — the specific H-2 concern. From an external IPv6 client:
curl -6 -s -o /dev/null -w "%{http_code} rem=%{header_x-ratelimit-remaining}\n" \
  -X POST http://srv1844961.hstgr.cloud/api/v1/auth/brand/login ...
# then spoof XFF over v6 and confirm it CANNOT open a fresh bucket.
# If v6 requests can rotate the bucket via XFF while v4 cannot, that is the
# docker-proxy / RemoteIpValve internal-proxies gap Kabir predicted.
```

If step 4 shows v6 XFF minting fresh buckets, the fix is `RemoteIpValve`'s `internal-proxies` regex
plus, ideally, `header_up X-Forwarded-For {remote_host}` in the Caddyfile so Caddy replaces rather
than appends — the edge fix Kabir's original B-1 recommended and that was never applied.

## Bottom line

- H-2 precondition: **CONFIRMED live** — IPv6 is present, routable, and served. Not moot.
- H-2 bypass itself: **UNTESTED** — needs the fixed build deployed first. This is the ordinary
  "code fixed ≠ deployed" gap that has held all day, applied to a security control.
- Nothing on the box was changed. No bucket was deliberately exhausted (probes stayed well under
  every limit). Read-only.

---

## UPDATE 2026-07-29 — the actual test RAN, after `native` deployed. FULL PASS.

> **Ran:** 2026-07-29 against `http://200.141.1.6` (v4) and `srv1844961.hstgr.cloud`
> (v6, `2a02:4780:63:a04e::1`), by Claude at the repo owner's instruction.
> **Precondition now satisfied:** `SERVER_FORWARD_HEADERS_STRATEGY=native` was deployed to the
> box's compose via SSH earlier this pass (`/docker/influora-test`, `docker compose up -d`,
> all containers recreated). So the build under test is finally the fixed one, not Wave 2.

Procedure = the four arms in "The actual test to run" above, `POST /api/v1/auth/brand/login`
with junk creds (the rate-limit filter runs before auth, so status is the signal; this filter
does **not** emit `X-RateLimit-Remaining`, so verdicts are read from HTTP status).

| Arm | What | Observed | Verdict |
|-----|------|----------|---------|
| v4 limiting | 12 unspoofed POSTs from real v4 | req 1–10 → `401`, req 11–12 → `429` | IP-keyed limiting live (limit 10) |
| v4 spoof | v4 bucket exhausted, then rotating `X-Forwarded-For: 9.9.9.9 / 8.8.4.4 / 203.0.113.5` | all `429` | **spoof ignored — CR-38 core fix confirmed** |
| v6 separation | v6 client while v4 bucket sat at `429` | v6 → `401` (fresh bucket) | real-IP keyed, **not** collapsed to Caddy's container IP → **Kabir branch-(c) ruled out** |
| v6 spoof | v6 bucket exhausted, then `X-Forwarded-For: 9.9.9.9 / 203.0.113.5 / 2001:4860:4860::8888` | all `429` | **v6 spoof ignored — H-2 IPv6 bypass does NOT exist on this build** |

Extra evidence: the **same** junk email was used on both stacks, yet v6 got fresh `401`s while v4
was `429` — so the limiter keys on the **real peer IP**, not the account, and discards the
client-supplied XFF on both stacks.

**Honest scope:** v4 and v6 were the operator's dual-stack egress — two genuinely different source
addresses (the one-v4-one-v6 "sharper check" this doc recommended), not two physically separate
networks. Sufficient for "distinguishes source IPs + ignores spoofed XFF." Not a load test.

**Bottom line: CR-38's behavioural bypass test — untested since 2026-07-28 for lack of a deployed
fix — is now RUN and PASSES on all four arms, including the IPv6 path.** The only thing between
CR-38 and `DONE` is the board's convention that Neha's live re-test performs the close.
