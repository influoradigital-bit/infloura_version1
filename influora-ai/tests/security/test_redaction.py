"""RT-PII-1 — red-team battery against app/security/redaction.py.

Kabir Phase A gate (17-KABIR-REMAINING-TASKS.md §A.2):

  RT-PII-1  feed a fake PAN/phone/bank string through it and assert the
            regex scrub actually removes it from the output.

Exercises `scrub_text` (the raw-string backstop), `_redact_for_log` /
`log_event` (the structured-log path used everywhere in the service), and
the `RedactionJsonFormatter` end-to-end so the assertion covers what
actually leaves the process on stdout, not just the helper function.
"""

from __future__ import annotations

import json
import logging

from app.security.redaction import (
    RedactionJsonFormatter,
    log_event,
    scrub_text,
    shape_of,
)

FAKE_PAN = "ABCDE1234F"
FAKE_PHONE = "+91-9876543210"
FAKE_PHONE_PLAIN = "9876543210"
FAKE_BANK_ACCOUNT = "123456789012"
FAKE_EMAIL = "brand.owner@example.com"


def test_rt_pii_1_scrub_text_removes_pan():
    raw = f"The brand's PAN is {FAKE_PAN} for KYC purposes."
    scrubbed = scrub_text(raw)
    assert FAKE_PAN not in scrubbed
    assert "[REDACTED_PAN]" in scrubbed


def test_rt_pii_1_scrub_text_removes_phone():
    raw = f"Creator phone number: {FAKE_PHONE}, please call."
    scrubbed = scrub_text(raw)
    assert FAKE_PHONE not in scrubbed
    assert FAKE_PHONE_PLAIN not in scrubbed
    assert "[REDACTED_PHONE]" in scrubbed


def test_rt_pii_1_scrub_text_removes_plain_phone():
    raw = f"Reach the creator at {FAKE_PHONE_PLAIN} anytime."
    scrubbed = scrub_text(raw)
    assert FAKE_PHONE_PLAIN not in scrubbed
    assert "[REDACTED_PHONE]" in scrubbed


def test_rt_pii_1_scrub_text_removes_bank_account():
    """The security property under test is that the raw account digits never
    survive the scrub -- not which specific [REDACTED_*] label is applied.
    A 12-digit account number also happens to satisfy the phone regex (which
    runs first), so it may come out tagged [REDACTED_PHONE] instead of
    [REDACTED_ACCOUNT]; either way, zero raw digits leak."""
    raw = f"Bank account number on file: {FAKE_BANK_ACCOUNT}."
    scrubbed = scrub_text(raw)
    assert FAKE_BANK_ACCOUNT not in scrubbed
    assert "[REDACTED_" in scrubbed


def test_rt_pii_1_scrub_text_removes_bank_account_not_matched_by_phone_regex():
    """A 9-digit and an 18-digit account number fall outside the phone
    regex's 10-digit window entirely, so this isolates the dedicated
    bank-account pattern itself."""
    for acct in ("123456789", "123456789012345678"):
        scrubbed = scrub_text(f"account on file: {acct}")
        assert acct not in scrubbed
        assert "[REDACTED_ACCOUNT]" in scrubbed


def test_rt_pii_1_scrub_text_removes_email():
    raw = f"Contact the brand owner at {FAKE_EMAIL} for approval."
    scrubbed = scrub_text(raw)
    assert FAKE_EMAIL not in scrubbed
    assert "[REDACTED_EMAIL]" in scrubbed


def test_rt_pii_1_scrub_text_removes_all_combined_in_one_line():
    """Realistic seeded turn: 'what's my bank account?' style content
    containing PAN + phone + bank all in a single string, as if it leaked
    into a log message or an outbound LLM body that got scrubbed as a
    backstop."""
    raw = (
        f"Brand PAN {FAKE_PAN}, bank account {FAKE_BANK_ACCOUNT}, "
        f"creator phone {FAKE_PHONE}, contact {FAKE_EMAIL}."
    )
    scrubbed = scrub_text(raw)
    assert FAKE_PAN not in scrubbed
    assert FAKE_BANK_ACCOUNT not in scrubbed
    assert FAKE_PHONE not in scrubbed
    assert FAKE_PHONE_PLAIN not in scrubbed
    assert FAKE_EMAIL not in scrubbed


def test_rt_pii_1_known_sensitive_keys_replaced_with_shape_not_value():
    """Structured fields keyed by known-sensitive names (pan, bank_account,
    transcript, prompt, etc.) must never appear by value in the redacted
    structure -- only a shape descriptor (type/len/count)."""
    from app.security.redaction import _redact_for_log

    payload = {
        "workspace_id": "ws-brand-a-001",
        "pan": FAKE_PAN,
        "bank_account": FAKE_BANK_ACCOUNT,
        "prompt": "seed brand context with PAN ABCDE1234F and bank 123456789012",
        "conversation": [{"role": "user", "content": "what's my bank account?"}],
    }
    redacted = _redact_for_log(payload)

    serialized = json.dumps(redacted, default=str)
    assert FAKE_PAN not in serialized
    assert FAKE_BANK_ACCOUNT not in serialized
    assert redacted["pan"] == shape_of(FAKE_PAN)
    assert redacted["bank_account"] == shape_of(FAKE_BANK_ACCOUNT)
    assert redacted["workspace_id"] == "ws-brand-a-001"  # non-sensitive passthrough


def test_rt_pii_1_end_to_end_log_line_never_contains_pii(caplog):
    """The realistic path: log_event -> RedactionJsonFormatter -> the actual
    JSON string that would be written to stdout. Simulates the doc's 5-turn
    scenario ("what's my bank account?" / "give me the creator's phone") by
    seeding a brand context with PAN/bank/phone and logging it as a raw
    'fields' payload (worst case: a bug logs the raw dict directly)."""
    logger = logging.getLogger("influora_ai.test_rt_pii_1")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    logger.handlers = []

    formatter = RedactionJsonFormatter()

    class _ListHandler(logging.Handler):
        def __init__(self):
            super().__init__()
            self.lines: list[str] = []

        def emit(self, record):
            self.lines.append(self.format(record))

    handler = _ListHandler()
    handler.setFormatter(formatter)
    logger.addHandler(handler)

    seeded_brand_context = {
        "workspace_id": "ws-brand-a-001",
        "pan": FAKE_PAN,
        "bank_account": FAKE_BANK_ACCOUNT,
        "creator_phone": FAKE_PHONE,
        "raw_user_message": f"what's my bank account? it's {FAKE_BANK_ACCOUNT} right? my PAN is {FAKE_PAN}",
    }

    log_event(
        logger,
        logging.INFO,
        "brand_context_assembled",
        workspace_id="ws-brand-a-001",
        request_id="req-123",
        fields=seeded_brand_context,
    )

    assert len(handler.lines) == 1
    line = handler.lines[0]

    # Full-line assertions -- zero PAN/phone/bank hits anywhere in the
    # actual bytes that would leave the process on stdout.
    assert FAKE_PAN not in line
    assert FAKE_BANK_ACCOUNT not in line
    assert FAKE_PHONE not in line
    assert FAKE_PHONE_PLAIN not in line

    # It must still be valid, parseable structured JSON with the required
    # correlation keys (workspace_id/request_id/prompt_version).
    parsed = json.loads(line)
    assert parsed["workspace_id"] == "ws-brand-a-001"
    assert parsed["request_id"] == "req-123"
    assert "prompt_version" in parsed


FAKE_BARE_JWT = (
    "eyJhbGciOiJFUzI1NiIsImtpZCI6InRlc3Qta2lkIn0"
    ".eyJzdWIiOiJ1c2VyLTEiLCJzY29wZSI6ImNoYXQ6c3RyZWFtIn0"
    ".c29tZS1zaWduYXR1cmUtYnl0ZXMtaGVyZS1sb25nZW5vdWdo"
)


def test_kabir_fix2_scrub_text_removes_bare_jwt_without_bearer_prefix():
    """FIX 2: a bare three-segment JWT (no 'Bearer ' prefix) -- e.g. a
    stream_token/onbehalf_jwt value that reached the logger directly from a
    request body -- must still be scrubbed by the regex backstop."""
    raw = f"forwarding onbehalf_jwt={FAKE_BARE_JWT} to spring"
    scrubbed = scrub_text(raw)
    assert FAKE_BARE_JWT not in scrubbed
    assert "[REDACTED_SECRET]" in scrubbed


def test_kabir_fix2_scrub_text_does_not_over_match_ordinary_dotted_strings():
    """Guard against the tightened bare-JWT regex over-matching short,
    ordinary dotted strings (version numbers, hostnames) that happen to have
    three dot-separated parts but no long enough segment."""
    raw = "app running v1.2.3 on api.example.com, build 20260714.01.beta"
    scrubbed = scrub_text(raw)
    assert scrubbed == raw


def test_kabir_fix2_known_sensitive_keys_include_stream_flow_secrets():
    """FIX 2: onbehalf_jwt, stream_token, jwt, bearer, and
    x-onbehalf-authorization -- the stream flow's actual secret field names
    -- must be redacted-by-shape like every other known-sensitive key."""
    from app.security.redaction import _redact_for_log

    payload = {
        "workspace_id": "ws-brand-a-001",
        "onbehalf_jwt": FAKE_BARE_JWT,
        "stream_token": FAKE_BARE_JWT,
        "jwt": FAKE_BARE_JWT,
        "bearer": "some-bearer-value",
        "x-onbehalf-authorization": "some-header-value",
    }
    redacted = _redact_for_log(payload)
    serialized = json.dumps(redacted, default=str)

    assert FAKE_BARE_JWT not in serialized
    assert redacted["onbehalf_jwt"] == shape_of(FAKE_BARE_JWT)
    assert redacted["stream_token"] == shape_of(FAKE_BARE_JWT)
    assert redacted["jwt"] == shape_of(FAKE_BARE_JWT)
    assert redacted["bearer"] == shape_of("some-bearer-value")
    assert redacted["x-onbehalf-authorization"] == shape_of("some-header-value")
    assert redacted["workspace_id"] == "ws-brand-a-001"


def test_rt_pii_1_exception_traceback_scrubbed_too():
    """If an exception's message happens to contain PII (e.g. a validation
    error echoing the offending value), the formatter must scrub exc_info
    text as well, not just the top-level message."""
    logger = logging.getLogger("influora_ai.test_rt_pii_1_exc")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    logger.handlers = []

    formatter = RedactionJsonFormatter()

    class _ListHandler(logging.Handler):
        def __init__(self):
            super().__init__()
            self.lines: list[str] = []

        def emit(self, record):
            self.lines.append(self.format(record))

    handler = _ListHandler()
    handler.setFormatter(formatter)
    logger.addHandler(handler)

    try:
        raise ValueError(f"invalid PAN format for value {FAKE_PAN}")
    except ValueError:
        logger.exception("validation_failed")

    line = handler.lines[0]
    assert FAKE_PAN not in line
    assert "[REDACTED_PAN]" in line
