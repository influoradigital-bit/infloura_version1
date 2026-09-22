# F-1787: security headers for influora.in (runbook for the server owner)

This runbook is for the server owner only. The agent that wrote it had no server access, so none of these steps have been run.

- Box: Utho VPS 150.241.245.242, nginx 1.26.3.
- The same nginx also serves snapsby.com. Nothing here touches that vhost.
- Every edit is followed by `nginx -t` and a `reload`. Never use `restart`.

**How to read each step.** Every step prints output first, then gives a STOP condition. If the output meets the STOP condition, do not continue. Run section 5 (Rollback) and send the output back.

## What ships

| Stage | File included | What it does |
|---|---|---|
| 1 | `influora-security-headers.conf` | Sends HSTS (1 day), nosniff, Referrer-Policy, X-Frame-Options DENY and Permissions-Policy. Also sends **Content-Security-Policy-Report-Only**, which blocks nothing and logs violations to the browser console. |
| 2 | `influora-security-headers.enforce.conf` | Same file, with one line changed: the CSP header becomes **Content-Security-Policy** (enforcing). |
| 3 | both files, edited in the repo | HSTS `max-age=86400` becomes `31536000`, after one clean day on stage 2. |

`ai.influora.in` gets nosniff and HSTS only, from `ai-influora-security-headers.conf`. It serves JSON and SSE, never HTML.

**Where the include goes.** Put the include line inside every `location {}` that serves the SPA or its files. Do not put it at `server {}` level, and do not put it inside `location /api/`. The reason: Spring already sends its own HSTS, X-Frame-Options, nosniff, Referrer-Policy and a strict CSP on `/api/` (checked on live 2026-09-22). A server-level include would be inherited by `/api/`, and every header would go out twice.

## 0. On your own machine: copy the files up

Run this from the repo root on branch `fix/f1787-security-headers`. Do not deploy from a dirty tree.

```bash
git status --short deploy/utho/nginx/          # must print nothing
diff deploy/utho/nginx/influora-security-headers.conf deploy/utho/nginx/influora-security-headers.enforce.conf
sha256sum deploy/utho/nginx/*.conf
scp deploy/utho/nginx/influora-security-headers.conf \
    deploy/utho/nginx/influora-security-headers.enforce.conf \
    deploy/utho/nginx/ai-influora-security-headers.conf \
    root@150.241.245.242:/root/
```

STOP if:
- the `diff` prints anything other than one `<` line and one `>` line, both starting with `add_header Content-Security-Policy`;
- `git status` prints anything.

Keep the `sha256sum` output. You will compare it in step 2.

## 1. Back up and read the vhost (changes nothing)

```bash
V=/etc/nginx/sites-available/influora.in
BAK=/root/influora.in.vhost.$(date +%Y%m%d-%H%M%S).bak
cp -a "$V" "$BAK" && echo "$BAK" > /root/F1787_BAK && ls -l "$BAK"
# 1a: is the file we edit the file nginx loads?
ls -l /etc/nginx/sites-enabled/ | grep -i influora
# 1b: server blocks, locations, existing headers and includes in the vhost
grep -nE 'server_name|listen|location|add_header|include|try_files|proxy_pass|error_page|return' "$V"
# 1c: add_header at http{} level, which a location with its own add_header stops inheriting
grep -rnE 'add_header' /etc/nginx/nginx.conf /etc/nginx/conf.d/ /etc/nginx/snippets/ 2>/dev/null
# 1d: nothing else claims these hostnames
grep -rlnE 'server_name[^;]*[[:space:]](www\.)?influora\.in[[:space:];]' /etc/nginx/sites-enabled/
```

The backup is stored in `/root`, not in `sites-enabled/` or `sites-available/`. Anything in `sites-enabled/` is loaded by nginx, including a `.bak` file.

STOP if:
- **1a:** `sites-enabled/influora.in` is not a symlink to `../sites-available/influora.in`. In that case, editing `$V` would change nothing live.
- **1b:** there is no `location /api/` that proxies to `127.0.0.1:8082` (the topology differs from what this runbook assumes).
- **1b:** there is more than one `listen 443` server block with `server_name influora.in`.
- **1d:** prints any file other than `/etc/nginx/sites-enabled/influora.in`.

Expected output, based on live responses on 2026-09-22 (no Cache-Control or any other added header was seen on `/` or `/assets/`):
- **1b:** shows no `add_header` lines.
- **1c:** shows none that apply to this server.

Write down each `location` line in the `:443` server that is **not** `/api/` and **not** `/.well-known/acme-challenge/`. Those are the locations you edit in step 2. The `:80` server block only redirects, so leave it alone.

## 2. Install the snippets and add the include lines

```bash
install -m 644 -o root -g root /root/influora-security-headers.conf         /etc/nginx/snippets/
install -m 644 -o root -g root /root/influora-security-headers.enforce.conf /etc/nginx/snippets/
sha256sum /etc/nginx/snippets/influora-security-headers*.conf
```

STOP if the hashes differ from the ones you kept in step 0.

Open the vhost and, as the **first line inside each location you wrote down in step 1**, add:

```nginx
        include /etc/nginx/snippets/influora-security-headers.conf;
```

For example, the SPA location becomes:

```nginx
    location / {
        include /etc/nginx/snippets/influora-security-headers.conf;
        try_files $uri $uri/ /index.html;      # <- whatever was already there, unchanged
    }
```

- **Do not** add the include to `location /api/`, to the ACME location, or at `server {}` level.
- If step 1c printed any `add_header` line that applies to this server, copy that line into each location you edit. Once a location has its own `add_header`, it stops inheriting from outside, so the line would otherwise be lost there.

Check that the edit contains only what you intended:

```bash
diff "$(cat /root/F1787_BAK)" /etc/nginx/sites-available/influora.in
grep -nE 'location|include /etc/nginx/snippets/influora-security' /etc/nginx/sites-available/influora.in
```

STOP if:
- the `diff` shows any `<` (removed) line;
- the `diff` shows any `>` line other than the include line (or the add_header lines copied from 1c);
- the second `grep` shows the include inside `location /api/`;
- the number of include lines differs from the number of locations you wrote down in step 1.

## 3. Test and reload

```bash
nginx -t
```

STOP if the output does not contain both `syntax is ok` and `test is successful`. The running config has not changed, so nothing is broken yet. Run section 5 to put the file back.

```bash
systemctl reload nginx && systemctl is-active nginx
```

STOP if the output is anything but `active`. Run section 5.

## 4. Verify from anywhere (anonymous curl, read-only)

```bash
H='^(strict-transport-security|x-content-type-options|referrer-policy|x-frame-options|permissions-policy|content-security-policy(-report-only)?):'
for u in https://influora.in/ https://www.influora.in/ https://influora.in/brand/dashboard https://influora.in/pricing https://influora.in/favicon.ico; do
  echo "== $u"; curl -sS -D - -o /dev/null "$u" | grep -iE "$H" | cut -c1-110
done
# one hashed asset (take the current name from the live index.html)
A=$(curl -sS https://influora.in/ | grep -oE '/assets/index-[A-Za-z0-9_-]+\.js' | head -1); echo "== $A"
curl -sS -D - -o /dev/null "https://influora.in$A" | grep -iE "$H" | cut -c1-110
```

Expected for **every** URL: exactly 6 lines.
- `strict-transport-security: max-age=86400`
- `x-content-type-options: nosniff`
- `referrer-policy: strict-origin-when-cross-origin`
- `x-frame-options: DENY`
- `permissions-policy: ...microphone=(self)...`
- `content-security-policy-report-only: default-src 'self'; ...`

STOP if any URL shows fewer or more than 6 lines. A location missed the include, or an include landed where it should not.

Then check the API, which must be **unchanged** (Spring's headers, one copy each, and no Report-Only header):

```bash
curl -sS -D - -o /dev/null https://influora.in/api/v1/health | grep -iE "$H"
for h in strict-transport-security x-frame-options x-content-type-options referrer-policy content-security-policy content-security-policy-report-only; do
  printf '%-40s %s\n' "$h" "$(curl -sS -D - -o /dev/null https://influora.in/api/v1/health | grep -ic "^$h:")"
done
curl -sS -o /dev/null -w 'api %{http_code}\n' https://influora.in/api/v1/health
curl -sS -D - -o /dev/null https://snapsby.com/ | grep -ciE '^content-security-policy-report-only:'
```

Expected:
- The first command shows `max-age=31536000 ; includeSubDomains`, `DENY`, `default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, and `no-referrer`.
- In the count table, every header shows `1` except `content-security-policy-report-only`, which shows `0`.
- `api 200`.
- snapsby shows `0`.

STOP on any other value.

### 4b. Stage 1 browser pass (on live, Report-Only)

Open Chrome on https://influora.in with DevTools open, Console tab, filter `Content Security Policy`. Violations appear with the prefix `[Report Only]`. Walk through the flows below and copy **every** report line into the F-1787 ticket:

1. Landing page. Also /pricing and /about.
2. Log in as a brand. Visit the dashboard, analytics Content Performance (Instagram thumbnails), and a deliverable review with a video.
3. **Wallet top-up.** Open the Razorpay modal and let it render its payment methods. You do not have to pay. If you do pay, pay the smallest amount, because this is the only way to exercise api.razorpay.com / lumberjack.
4. Escrow funding modal, the same way.
5. Meera chat: send one message (streaming). Then use voice: allow the microphone prompt, speak, and let the reply play (blob audio).
6. Contract "download PDF" (opens a blob: window).
7. Log in as a creator. Open the creator Meera and the analytics pages.
8. Repeat step 1 on https://www.influora.in/ (the API is cross-origin from www).

The only expected violation is the inline script from **GTM Custom HTML tag_id 3** (a duplicate Meta Pixel bootstrap for pixel 624599723914394). Delete that tag in GTM, since tag_id 5 already fires the same pixel, and publish the container.

**Already verified locally (2026-09-22), anonymous pages only.** The live bundle (`index-BRQ9d70T.js`) was served by `test-server.mjs` with the **enforcing** snippet and walked in Chrome: `/`, `/pricing`, `/brand/login`, `/creator/login`, `/brand/register`, `/@<handle>`. Across all six loads the **only** violation was tag_id 3 (`sha256-BQYBBZhXDMKz7pJ7pPmbeXAf9WXOnc1yWwr+Da4ZOSo=`), and the Meta Pixel still loaded via tag_id 5 (`connect.facebook.net`, `www.facebook.com`), as did GTM, GA4 and Clarity (`www`, `scripts`, `k.clarity.ms`). What that walk could NOT reach is everything behind login — steps 2-7 above, including all of Razorpay. Those remain unverified until this browser pass.

Stage 1 passes when a second walk shows **zero** `[Report Only]` lines. Any other violation means the policy must be widened **in the repo** (in both snippet files, re-diffed), re-installed through steps 0 to 4, and walked again. Never widen it by hand-editing the file on the server.

### 4c. Stage 2: enforce (only after 4b passes)

Do not start stage 2 until ALL of these are true — each is a yes/no, not a judgement:

1. GTM tag_id 3 is deleted **and the container is published** (a deleted-but-unpublished tag still fires).
2. The 4b walk, repeated after the GTM publish, shows **zero** `[Report Only]` lines, including the logged-in flows and a Razorpay modal render.
3. Take a fresh backup now (`cp -a` the vhost to `/root/influora.in.vhost.<stamp>.stage2.bak`) — not the day-1 copy.
4. Strongly recommended: ledger **F-1789** has shipped (a `securitypolicyviolation` listener in the SPA posting to the existing `[CLIENT_ERROR_REPORT]` log). Without it, stage 2 is enforced blind: the only signal that a user path broke is someone reporting it. If you enforce without F-1789, say so in the ticket.

```bash
sed -i 's#/etc/nginx/snippets/influora-security-headers.conf;#/etc/nginx/snippets/influora-security-headers.enforce.conf;#' /etc/nginx/sites-available/influora.in
grep -n 'include /etc/nginx/snippets/influora-security' /etc/nginx/sites-available/influora.in
nginx -t
```

STOP if `grep` shows any include that does not end in `.enforce.conf;`, or if `nginx -t` fails.

```bash
systemctl reload nginx && systemctl is-active nginx
curl -sS -D - -o /dev/null https://influora.in/ | grep -iE '^content-security-policy(-report-only)?:' | cut -c1-60
```

Expected: exactly one line, starting `content-security-policy: default-src 'self'`, with no `-report-only`. Then repeat the 4b walk. A violation now shows as `Refused to ...` and **is** a break. Roll back to stage 1 with the reverse `sed` (swap the two filenames), then `nginx -t`, then `reload`.

### 4d. Stage 3: HSTS ramp (one clean day after 4c)

"Clean day" means, measured over the 24 hours after the stage-2 reload:
- **With F-1789 shipped:** zero `[CLIENT_ERROR_REPORT]` entries of type CSP violation in the API logs
  (`docker logs influora-api --since 24h 2>&1 | grep CLIENT_ERROR_REPORT | grep -ci 'securitypolicyviolation\|csp'` prints `0`), **and**
- **Either way:** the full 4b walk repeated once more shows zero `Refused to` lines.

Without F-1789 only the second check is available, and it covers only the paths the walk visits.

The ramp buys less than it looks: Spring already sends `max-age=31536000 ; includeSubDomains` on every
`/api/` response, so most browsers that have used the app already hold a 1-year policy. The 1-day value
on HTML is a low-cost courtesy during rollout, not a real reversibility guarantee.

In the repo, change `max-age=86400` to `max-age=31536000` in **both** influora snippets and in `ai-influora-security-headers.conf`. Then re-run steps 0, 2 (install and sha check only), 3 and 4.

Do not add `includeSubDomains` or `preload` here. Those are separate decisions, and preload is effectively irreversible. Note that Spring already sends `includeSubDomains` on `/api/`.

## ai.influora.in (nosniff + HSTS only)

The vhost is probably `/etc/nginx/sites-available/ai-influora.conf`, installed by `deploy/utho/install-ai-vhost.sh`. Confirm that before editing:

```bash
grep -rlnE 'server_name[^;]*ai\.influora\.in' /etc/nginx/sites-enabled/
ls -l /etc/nginx/sites-enabled/ | grep -i ai
curl -sS -D - -o /dev/null https://ai.influora.in/healthz | grep -iE '^(strict-transport-security|x-content-type-options):'
```

STOP if:
- `grep` prints anything other than exactly one file;
- that file is not a symlink into `sites-available`.

Call the real file `AIV` from here on.

```bash
AIV=/etc/nginx/sites-available/ai-influora.conf      # <- the file the grep printed, resolved
AIBAK=/root/ai-influora.vhost.$(date +%Y%m%d-%H%M%S).bak
cp -a "$AIV" "$AIBAK" && echo "$AIBAK" > /root/F1787_AI_BAK
grep -nE 'listen|location|add_header|proxy_hide_header|include' "$AIV"
install -m 644 -o root -g root /root/ai-influora-security-headers.conf /etc/nginx/snippets/
```

Add `include /etc/nginx/snippets/ai-influora-security-headers.conf;` as the first line inside the `:443` server's `location / {`. That is the one with `proxy_pass` and `proxy_buffering off`. Leave the SSE lines as they are. Then run:

```bash
diff "$(cat /root/F1787_AI_BAK)" "$AIV"
nginx -t
```

STOP if:
- the diff shows anything other than the one added include line;
- `nginx -t` fails.

```bash
systemctl reload nginx && systemctl is-active nginx
for h in strict-transport-security x-content-type-options; do
  printf '%-28s %s\n' "$h" "$(curl -sS -D - -o /dev/null https://ai.influora.in/healthz | grep -ic "^$h:")"
done
curl -sS -o /dev/null -w 'ai healthz %{http_code}\n' https://ai.influora.in/healthz
```

Expected: both headers show `1`, and `ai healthz 200`. That `curl` is the whole proof for this vhost. Do NOT use a Meera chat message as evidence here: the live SPA streams Meera over same-origin `/api/v1/meera`, never through `ai.influora.in` (0 references in the live bundle), so a working chat says nothing about this change.

## 5. ROLLBACK (one block, safe to run at any point)

This rollback **removes only the include lines this runbook added**. It deliberately does NOT copy
the step-1 backup back: this rollout spans several days on a box another session also deploys to,
and restoring a days-old copy would silently undo any unrelated vhost change made in between (a
new location, a certbot edit, a proxy change).

```bash
for F in /etc/nginx/sites-available/influora.in \
         "$(readlink -f "$(grep -rlE 'server_name[^;]*ai\.influora\.in' /etc/nginx/sites-enabled/ | head -1)")"; do
  [ -f "$F" ] || continue
  cp -a "$F" "/root/$(basename "$F").pre-rollback.$(date +%Y%m%d-%H%M%S)"
  # Removes the include STATEMENT, not the whole line: a whole-line delete would take a
  # "location /x { include ...; }" one-liner with it and break the config. Tested on a fake
  # vhost with own-line, enforce, one-liner and unrelated includes: only ours removed.
  sed -i 's#[[:space:]]*include /etc/nginx/snippets/\(ai-\)\?influora-security-headers\(\.enforce\)\?\.conf;##' "$F"
  echo "$F: remaining F-1787 includes = $(grep -c 'influora-security-headers' "$F")"
done
nginx -t && systemctl reload nginx && systemctl is-active nginx
curl -sS -D - -o /dev/null https://influora.in/ | grep -ciE '^(content-security-policy|content-security-policy-report-only|x-frame-options):'
```

Expected: each file shows `remaining F-1787 includes = 0`, then `active`, then `0`.

Only if that fails: the step-1 copy is at `$(cat /root/F1787_BAK)`. **Diff it against the live file
before restoring** — anything in the diff other than the F-1787 include lines is someone else's
change that a restore would erase.

- The snippet files left in `/etc/nginx/snippets/` are inert once nothing includes them.
- HSTS is sticky: browsers that received `max-age=86400` keep it for up to one day after rollback. That is harmless, because the site is HTTPS-only anyway.

## Every frontend deploy from now on (the CSP hash gate)

The policy has **no** script hashes today, because the live `index.html` has no executable inline script. Any future inline `<script>` would be blocked once stage 2 is live. This includes a theme-flash snippet, an inline GTM bootstrap, or anything that `prerender.mjs` writes into `dist/**/index.html`. Before every frontend deploy, run this against the exact `dist/` you are about to copy:

```bash
npm run build          # also runs generate-sitemap + prerender
node deploy/utho/nginx/csp-inline-hashes.mjs deploy/utho/nginx/influora-security-headers.enforce.conf dist
```

It must exit `0`. On failure it prints each missing `'sha256-...'`:
1. Add each missing hash to `script-src` in **both** snippet files.
2. Run steps 0 to 4.
3. Only then deploy the frontend.

The hash is computed over the script text **after** CRLF is normalized to LF, which is what the browser hashes. Hashing the raw file bytes of a CRLF file gives a hash the browser rejects (checked in Chrome 2026-09-22). The live `index.html` has CRLF line endings.

## Local proof before any of this

```bash
node deploy/utho/nginx/test-server.mjs deploy/utho/nginx/influora-security-headers.enforce.conf
# -> http://localhost:3099 serves .proof-os/tasks/T-SECHDR-0922/live-bundle with the headers
#    parsed from the snippet file (never a retyped copy); open it in Chrome and read the console.
node deploy/utho/nginx/test-server.mjs deploy/utho/nginx/influora-security-headers.conf --print
```

The local proof covers only anonymous pages. Razorpay and Meera voice need the authenticated live walk in 4b.

Loading the page locally **fires the real GA4, Clarity and Meta Pixel tags** from localhost. Do it in a browser with a tracker blocker off only if the analytics owner accepts a few localhost hits. Otherwise, rely on the 4b walk.
