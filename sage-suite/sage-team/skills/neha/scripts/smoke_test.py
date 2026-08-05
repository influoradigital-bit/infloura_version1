#!/usr/bin/env python3
"""
smoke_test.py — Headless browser smoke-test runner for the `neha` E2E skill.

Drives Chromium (Playwright) through a live URL like a real user: loads pages,
walks the main nav, optionally runs named flows (login, submit a form, etc.),
and captures the signals that matter — uncaught JS errors, console errors,
failed network requests, broken links, and broken images. Emits a findings
JSON in the exact shape build_report.py consumes, plus screenshots.

IMPORTANT — reachability:
  This runner only works when the TARGET URL is reachable from wherever the
  script runs. In a restricted Cowork cloud sandbox, outbound egress is
  allowlisted and arbitrary sites return 403, so this script cannot reach the
  user's live site from there. In that case, drive the user's real browser via
  Claude in Chrome instead (see SKILL.md). Use this script when the target is
  reachable: on-computer mode, a public CI runner, or a localhost app running
  in the same environment.

Usage:
  python3 smoke_test.py --url https://staging.example.com \
      --out ./e2e_out --task "Staging smoke" [--flows flows.json] [--max-pages 8] \
      [--mobile]

flows.json (optional) — a list of user journeys:
[
  {
    "name": "Login",
    "critical": true,
    "steps": [
      {"action": "goto", "url": "/login"},
      {"action": "fill", "selector": "#email", "value": "test@demo.com"},
      {"action": "fill", "selector": "#password", "value": "secret"},
      {"action": "click", "selector": "button[type=submit]"},
      {"action": "expect_text", "text": "Dashboard"}
    ]
  }
]
Supported step actions: goto, click, fill, expect_text, expect_visible, wait, screenshot.
"""

import argparse
import json
import os
import re
from urllib.parse import urljoin, urlparse

try:
    from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout
except Exception as e:  # pragma: no cover
    raise SystemExit("Playwright not installed. pip install playwright (Chromium is preinstalled here).")


def add(findings, severity, title, where, issue, fix):
    findings.append({
        "tester": "Neha", "type": "E2E", "severity": severity,
        "title": title, "where": where, "issue": issue, "fix": fix,
    })


def same_host(a, b):
    return urlparse(a).netloc == urlparse(b).netloc


def run(args):
    findings = []
    os.makedirs(args.out, exist_ok=True)
    shots = os.path.join(args.out, "screenshots")
    os.makedirs(shots, exist_ok=True)

    seen_console = set()  # dedupe console errors by (text)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx_kwargs = {"viewport": {"width": 390, "height": 844}, "is_mobile": True} if args.mobile \
            else {"viewport": {"width": 1366, "height": 900}}
        context = browser.new_context(**ctx_kwargs)
        page = context.new_page()

        page_errors = []   # uncaught JS exceptions on current page
        bad_responses = [] # status >= 400
        console_errors = []

        page.on("pageerror", lambda e: page_errors.append(str(e)))
        page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
        page.on("response", lambda r: bad_responses.append((r.url, r.status)) if r.status >= 400 else None)

        # --- load homepage ---
        try:
            resp = page.goto(args.url, wait_until="load", timeout=args.timeout)
        except PWTimeout:
            add(findings, "Critical", "Homepage did not load (timeout)", args.url,
                "The page never reached 'load' within the timeout.", "Check server/DNS/TLS and page weight.")
            _finish(findings, args, browser)
            return findings
        except Exception as ex:
            add(findings, "Critical", "Homepage unreachable", args.url,
                f"Navigation failed: {ex}", "Verify the URL is live and reachable from this environment.")
            _finish(findings, args, browser)
            return findings

        if resp and resp.status >= 500:
            add(findings, "Critical", f"Homepage returned {resp.status}", args.url,
                "Server error on the entry page.", "Fix the server error before anything else.")
        elif resp and resp.status >= 400:
            add(findings, "High", f"Homepage returned {resp.status}", args.url,
                "Client error on the entry page.", "Check routing / auth gate on the landing route.")

        page.screenshot(path=os.path.join(shots, "home.png"), full_page=True)

        title = page.title()
        if not title.strip():
            add(findings, "Medium", "Missing page <title>", args.url,
                "The homepage has no title — hurts SEO and tab clarity.", "Set a descriptive <title>.")

        # broken images
        broken_imgs = page.evaluate(
            "Array.from(document.images).filter(i => !i.complete || i.naturalWidth === 0).map(i => i.src)")
        for src in broken_imgs[:10]:
            add(findings, "Low", "Broken image", src,
                "Image failed to render (naturalWidth 0).", "Fix the asset path or remove the image.")

        # collect internal links for the crawl
        links = page.evaluate(
            "Array.from(document.querySelectorAll('a[href]')).map(a => a.href)")
        internal = []
        for href in links:
            if href.startswith("http") and same_host(args.url, href) and "#" not in href:
                if href not in internal:
                    internal.append(href)

        _drain_page_signals(findings, args.url, page_errors, console_errors, bad_responses, seen_console)

        # --- crawl key internal pages (smoke) ---
        for link in internal[: max(0, args.max_pages)]:
            page_errors.clear(); console_errors.clear(); bad_responses.clear()
            try:
                r = page.goto(link, wait_until="load", timeout=args.timeout)
            except Exception:
                add(findings, "Medium", "Link failed to load", link,
                    "A linked page did not load.", "Check the route / target.")
                continue
            if r and r.status >= 500:
                add(findings, "Critical", f"Page returned {r.status}", link,
                    "Server error on an internal page.", "Fix the server error.")
            elif r and r.status >= 400:
                add(findings, "Medium", f"Broken link ({r.status})", link,
                    "Internal link resolves to an error page.", "Fix or remove the link.")
            _drain_page_signals(findings, link, page_errors, console_errors, bad_responses, seen_console)

        # --- named flows (real-user journeys) ---
        if args.flows and os.path.exists(args.flows):
            with open(args.flows, encoding="utf-8") as fh:
                flows = json.load(fh)
            for flow in flows:
                _run_flow(findings, page, args, flow, shots)

        _finish(findings, args, browser)
    return findings


def _drain_page_signals(findings, url, page_errors, console_errors, bad_responses, seen_console):
    for err in page_errors:
        add(findings, "High", "Uncaught JavaScript error", url,
            f"Runtime exception on the page: {err[:200]}", "Fix the JS error; it can break interactivity.")
    for text in console_errors:
        key = text[:120]
        if key in seen_console:
            continue
        seen_console.add(key)
        add(findings, "Medium", "Console error", url,
            f"console.error on load: {text[:200]}", "Investigate and resolve the logged error.")
    for u, status in bad_responses:
        sev = "Critical" if status >= 500 else "Medium"
        add(findings, sev, f"Failed request ({status})", u,
            "A network request on this page failed.", "Fix the endpoint/asset or handle the failure gracefully.")


def _run_flow(findings, page, args, flow, shots):
    name = flow.get("name", "flow")
    critical = flow.get("critical", False)
    fail_sev = "Critical" if critical else "High"
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    for i, step in enumerate(flow.get("steps", [])):
        act = step.get("action")
        try:
            if act == "goto":
                page.goto(urljoin(args.url, step["url"]), wait_until="load", timeout=args.timeout)
            elif act == "click":
                page.click(step["selector"], timeout=args.timeout)
            elif act == "fill":
                page.fill(step["selector"], step.get("value", ""), timeout=args.timeout)
            elif act == "wait":
                page.wait_for_timeout(int(step.get("value", 1000)))
            elif act == "expect_visible":
                page.wait_for_selector(step["selector"], state="visible", timeout=args.timeout)
            elif act == "expect_text":
                page.wait_for_function(
                    "t => document.body && document.body.innerText.includes(t)",
                    arg=step["text"], timeout=args.timeout)
            elif act == "screenshot":
                page.screenshot(path=os.path.join(shots, f"{slug}-{i}.png"), full_page=True)
        except Exception as ex:
            page.screenshot(path=os.path.join(shots, f"{slug}-FAIL-{i}.png"), full_page=True)
            add(findings, fail_sev, f"Flow '{name}' broke at step {i+1} ({act})",
                step.get("selector") or step.get("url") or step.get("text", ""),
                f"The user journey could not be completed: {str(ex)[:160]}",
                "Fix the step so the flow works end-to-end for real users.")
            return  # stop this flow at first failure, like a user would be stuck


def _finish(findings, args, browser):
    browser.close()
    out = {
        "task": args.task,
        "date": args.date,
        "target": args.url,
        "stages_run": ["E2E"],
        "findings": findings,
    }
    path = os.path.join(args.out, "findings.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)
    counts = {}
    for f in findings:
        counts[f["severity"]] = counts.get(f["severity"], 0) + 1
    print(f"Findings: {counts or 'none — clean smoke run'}")
    print(f"Wrote: {path}")
    print(f"Screenshots: {os.path.join(args.out, 'screenshots')}/")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--out", default="./e2e_out")
    ap.add_argument("--task", default="E2E smoke test")
    ap.add_argument("--date", default="")
    ap.add_argument("--flows", default=None)
    ap.add_argument("--max-pages", type=int, default=8)
    ap.add_argument("--timeout", type=int, default=20000)
    ap.add_argument("--mobile", action="store_true")
    args = ap.parse_args()
    run(args)


if __name__ == "__main__":
    main()
