#!/usr/bin/env python3
"""
Falsification harness for ci/secret-scan.py (EV-006).

A gate that passes everything looks exactly like a gate that works, so this proves the scanner
REFUSES what it exists to refuse. It plants canary files in an untracked, non-ignored directory
(ci/_secret_scan_canary/) -- never staged, never committed, always deleted in `finally` -- and runs
the real gate as a subprocess against the real tree each time:

  1. the tree as it stands is clean (this also proves no pattern matches its own definition or
     the comments describing it, since the gate and this harness are scanned too);
  2. one synthetic live-format value per rule is refused, and the value never appears in the
     gate's output;
  3. a provider-shaped key inside a test source tree is still refused;
  4. the committed REPLACE_WITH_YOUR_* placeholders and other placeholder shapes pass;
  5. removing the canary returns the gate to green.

Every synthetic value is assembled at runtime from fragments, so this file carries no literal
that the gate would flag. Values come from a seeded PRNG so a failure reproduces.

Usage:  python ci/secret-scan-falsify.py
Exit:   0 every case behaved -- 1 a case misbehaved -- 2 harness could not set up
"""

from __future__ import annotations

import random
import shutil
import string
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GATE = ROOT / "ci" / "secret-scan.py"
CANARY_DIR = ROOT / "ci" / "_secret_scan_canary"

rng = random.Random(6006)
ALNUM = string.ascii_letters + string.digits
LOWER_DIGIT = string.ascii_lowercase + string.digits
UPPER_DIGIT = string.ascii_uppercase + string.digits
HEX = "0123456789abcdef"


def rand(chars: str, n: int) -> str:
    # Keep synthetic values free of the placeholder markers the gate honours (e.g. "mock").
    while True:
        value = "".join(rng.choice(chars) for _ in range(n))
        lower = value.lower()
        if not any(m in lower for m in ("mock", "fake", "xxxx", "your", "sample", "dummy")):
            return value


def live_cases() -> list[tuple[str, str, str]]:
    """(expected rule id, file line to plant, the secret value that must not be echoed)."""
    cases = []
    v = "sk-" + "ant-api03-" + rand(ALNUM + "-_", 95)
    cases.append(("anthropic-api-key", "LLM_KEY_VALUE=" + v, v))
    v = "sk-" + "proj-" + rand(ALNUM, 48)
    cases.append(("openai-project-key", "value = '" + v + "'", v))
    v = "sk" + "_" + rand(LOWER_DIGIT, 8) + "_" + rand(ALNUM, 24)
    cases.append(("sarvam-api-key", "voice: " + v, v))
    v = "AI" + "za" + rand(ALNUM + "-_", 35)
    cases.append(("google-api-key", "maps=" + v, v))
    v = "rzp_" + "live_" + rand(ALNUM, 14)
    cases.append(("razorpay-live-key-id", "const k = '" + v + "';", v))
    v = "AK" + "IA" + rand(UPPER_DIGIT, 16)
    cases.append(("aws-access-key-id", "id " + v, v))
    v = "gh" + "p_" + rand(ALNUM, 36)
    cases.append(("github-token", "token " + v, v))
    v = "xo" + "xb-" + rand(ALNUM, 30)
    cases.append(("slack-token", "hook " + v, v))
    body = rand(ALNUM + "+/", 64)
    cases.append(("private-key-pem", "-----BEGIN " + "PRIVATE KEY-----\n" + body + "\n-----END " + "PRIVATE KEY-----", body))
    v = rand(HEX, 64)
    cases.append(("secret-assignment", "R2_SECRET_" + "ACCESS_KEY=" + v, v))
    v = rand(HEX, 32)
    cases.append(("secret-assignment", "R2_ACCESS_" + "KEY_ID=" + v, v))
    v = rand(string.digits, 6) + rand(ALNUM, 21)
    cases.append(("secret-assignment", "MSG91_" + "AUTH_KEY=" + v, v))
    v = rand(string.digits, 6) + rand(ALNUM, 21)
    cases.append(("secret-assignment", "MSG91_" + "TOKEN_AUTH=" + v, v))
    v = rand(ALNUM, 40)
    cases.append(("secret-assignment", "  JWT_ACCESS_" + "SECRET: " + v, v))
    v = rand(ALNUM, 32)
    cases.append(("spring-secret-default", "key: ${PAYMENT_" + "API_KEY:" + v + "}", v))
    return cases


PLACEHOLDER_LINES = [
    "ANTHROPIC_" + "API_KEY=REPLACE_WITH_YOUR_ANTHROPIC_API_KEY",
    "SARVAM_" + "API_KEY=REPLACE_WITH_YOUR_SARVAM_API_KEY",
    "GEMINI_" + "API_KEY=REPLACE_WITH_YOUR_GEMINI_API_KEY",
    "R2_SECRET_" + "ACCESS_KEY=REPLACE_WITH_YOUR_R2_SECRET_KEY",
    "MSG91_" + "AUTH_KEY=REPLACE_WITH_YOUR_MSG91_AUTH_KEY",
    "JWT_ACCESS_" + "SECRET=dev-access-secret-change-me-at-least-32-bytes-long",
    "key-secret: ${RAZORPAY_KEY_" + "SECRET:REPLACE_WITH_YOUR_RAZORPAY_SECRET}",
    "DB_" + "PASSWORD=${DB_PASSWORD}",
    "AI" + "za" + "SyYOUR_GOOGLE_KEY_GOES_HERE_0000000000",
    "SMTP_" + "PASSWORD=short",
]


def run_gate() -> tuple[int, str]:
    proc = subprocess.run([sys.executable, str(GATE)], capture_output=True, text=True, cwd=ROOT)
    return proc.returncode, proc.stdout + proc.stderr


def plant(rel: str, content: str) -> None:
    path = CANARY_DIR / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content + "\n", encoding="utf-8")


def clear() -> None:
    shutil.rmtree(CANARY_DIR, ignore_errors=True)


def main() -> int:
    if CANARY_DIR.exists():
        print(f"falsify: {CANARY_DIR} already exists; refusing to overwrite it", file=sys.stderr)
        return 2
    probe = subprocess.run(
        ["git", "-C", str(ROOT), "check-ignore", "-q", str(CANARY_DIR / "probe.env")]
    )
    if probe.returncode == 0:
        print("falsify: canary dir is gitignored, the gate would never see it", file=sys.stderr)
        return 2

    failures: list[str] = []
    try:
        rc, out = run_gate()
        if rc != 0:
            failures.append(f"baseline: gate is red on the real tree (exit {rc})\n{out}")

        for rule, line, value in live_cases():
            plant("canary.env", line)
            rc, out = run_gate()
            if rc != 1 or rule not in out:
                failures.append(f"{rule}: planted value NOT refused (exit {rc})")
            if value in out:
                failures.append(f"{rule}: gate output echoed the secret value")
            clear()

        v = "sk-" + "ant-api03-" + rand(ALNUM, 95)
        plant("src/test/java/CanaryFixtureTest.java", 'static final String K = "' + v + '";')
        rc, out = run_gate()
        if rc != 1 or "anthropic-api-key" not in out:
            failures.append(f"provider key in a test tree NOT refused (exit {rc})")
        clear()

        plant("placeholders.env", "\n".join(PLACEHOLDER_LINES))
        rc, out = run_gate()
        if rc != 0:
            failures.append(f"placeholders were refused (exit {rc})\n{out}")
        clear()

        rc, out = run_gate()
        if rc != 0:
            failures.append(f"gate did not return to green after the canary was removed (exit {rc})")
    finally:
        clear()

    if failures:
        print("secret-scan falsification FAILED:")
        for f in failures:
            print("  - " + f)
        return 1
    print(f"secret-scan falsification: all {len(live_cases()) + 4} cases behaved.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
