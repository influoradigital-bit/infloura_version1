"""Structured JSON logging + redaction backstop.

Kabir guardrail #3: never log full prompts, brand catalog contents, transcripts,
audio, secrets, or PII. Logs shapes/lengths/counts, not values. Every log line is
keyed by workspace_id + request_id + prompt_version so any turn is traceable
without ever holding the sensitive payload itself.

The regex scrub below is a BACKSTOP — code should already avoid logging raw
values — but if a raw string ever does reach the logger, PAN/phone/bank-account
patterns get scrubbed before the line leaves the process.
"""

from __future__ import annotations

import json
import logging
import re
import sys
from datetime import datetime, timezone
from typing import Any

from app.config import PROMPT_VERSION

# --- Backstop regex patterns -------------------------------------------------
# Indian PAN: 5 letters, 4 digits, 1 letter (e.g. ABCDE1234F)
_PAN_RE = re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")
# Phone numbers: optional +country code, 10-15 digits, allow separators.
#
# F-13 (inverse failure): this used to be `(?<!\d)…\d{10}(?!\d)`, which matched
# ANY 10-digit run — including the digits of a decimal. `cost_usd=0.0123456789`
# scrubbed to `[REDACTED_PHONE]`, so the CFO's daily cost reports read back
# nothing. A digit run that is part of a decimal number (a `.` or `,` on either
# side) is not a phone number; the lookarounds now say so.
_PHONE_RE = re.compile(r"(?<![\d.,])(?:\+?\d{1,3}[-\s]?)?\d{10}(?![\d.,]*\d)")
# Bank account / IBAN-ish: 9-18 digit runs (typical Indian bank account length).
# Same F-13 decimal exclusion as the phone pattern above — the fractional digits
# of `0.0123456789` are not an account number.
_BANK_ACCOUNT_RE = re.compile(r"(?<![\d.,])\d{9,18}(?![\d.,]*\d)")
# Generic secret-looking tokens: sk-..., Bearer <jwt>, long base64/hex blobs
_SECRET_RE = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9\-_.]{20,}|[A-Za-z0-9+/]{40,}={0,2})"
)
# Bare three-segment JWT (no "Bearer " prefix) -- e.g. a stream_token/onbehalf_jwt
# value logged directly from a request body rather than an Authorization header.
# Each segment must be >=8 chars of the base64url alphabet (header/payload/
# signature are all base64url, never plain base64 with +/) AND at least one
# segment must be >=20 chars, so ordinary short dotted strings (version numbers,
# hostnames, "a.b.c"-shaped identifiers) never match -- real JWT payload
# segments are comfortably longer than 20 chars even for a minimal claim set.
_JWT_SEGMENT_RE = re.compile(r"\b([A-Za-z0-9_-]{8,})\.([A-Za-z0-9_-]{8,})\.([A-Za-z0-9_-]{8,})\b")
_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")

_REDACT_KEYS = {
    "prompt",
    "prompts",
    "content",
    "transcript",
    "raw_transcript",
    "cleaned_text",
    "audio",
    "audio_bytes",
    "authorization",
    "token",
    "access_token",
    "api_key",
    "secret",
    "password",
    "pan",
    "bank_account",
    "upi",
    "conversation",
    "conversation_history",
    "brand_catalog",
    "product_catalog",
    # Kabir red-team FIX 2 — the stream-flow's actual secret field names
    # (app/routes/chat.py's request body / headers) weren't in this set.
    "onbehalf_jwt",
    "stream_token",
    "jwt",
    "bearer",
    "x-onbehalf-authorization",
    # Creator AI Co-pilot (Priya R1 ruling, P2 row) -- Tier-1's
    # /internal/creator-suggestion route never receives caption text at all
    # (no `caption_snippet` field, per the R1 Conflict-5 cut), so these keys
    # are forward cover: Tier-2's caption-touching routes and any other
    # creator-flow logging (Vikram's Java side / future routes) that might
    # log caption/IG text get the same redaction backstop from day one,
    # rather than adding it reactively once such a route exists.
    "caption",
    "captions",
    "ig_handle",
}


def _scrub_bare_jwts(value: str) -> str:
    """Backstop for a bare (no "Bearer " prefix) three-segment JWT reaching
    the logger -- e.g. `stream_token`/`onbehalf_jwt` values, which are raw
    JWTs, not Authorization-header-shaped strings, so `_SECRET_RE`'s
    `Bearer\\s+...` branch never sees them."""

    def _replace(match: re.Match[str]) -> str:
        if any(len(segment) >= 20 for segment in match.groups()):
            return "[REDACTED_SECRET]"
        return match.group(0)

    return _JWT_SEGMENT_RE.sub(_replace, value)


def scrub_text(value: str) -> str:
    """Regex backstop scrub for any raw string that reaches the logger."""
    value = _SECRET_RE.sub("[REDACTED_SECRET]", value)
    value = _scrub_bare_jwts(value)
    value = _PAN_RE.sub("[REDACTED_PAN]", value)
    value = _EMAIL_RE.sub("[REDACTED_EMAIL]", value)
    value = _PHONE_RE.sub("[REDACTED_PHONE]", value)
    value = _BANK_ACCOUNT_RE.sub("[REDACTED_ACCOUNT]", value)
    return value


def shape_of(value: Any, *, reveal_keys: bool = True) -> Any:
    """Convert a value into a safe 'shape' descriptor: lengths/counts/types, never
    the raw value itself. Use this instead of logging payloads directly.

    F-13: for a dict this returned `sorted(value.keys())` unconditionally —
    including for values reached under a SENSITIVE key. A brand catalog is a
    dict keyed BY PRODUCT NAME, so `{"brand_catalog": {"Chanel No 5 (SKU 991)": 1}}`
    logged `{"keys": ["Chanel No 5 (SKU 991)"]}`: the catalog contents this
    module's docstring promises never to log. `reveal_keys=False` (used for every
    redacted key) emits only a count. Key names that ARE emitted are scrubbed.
    """
    if value is None:
        return None
    if isinstance(value, str):
        return {"type": "str", "len": len(value)}
    if isinstance(value, (bytes, bytearray)):
        return {"type": "bytes", "len": len(value)}
    if isinstance(value, (list, tuple, set, frozenset)):
        return {"type": type(value).__name__, "count": len(value)}
    if isinstance(value, dict):
        if not reveal_keys:
            return {"type": "dict", "key_count": len(value)}
        return {"type": "dict", "keys": sorted(scrub_text(str(k)) for k in value)}
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value
    return {"type": type(value).__name__}


# Scalars that are safe to emit verbatim. Everything else is reduced to a shape
# descriptor — see _redact_for_log.
_SAFE_SCALARS = (bool, int, float)


def _redact_for_log(obj: Any) -> Any:
    """Recursively redact a structure before it is serialized into a log line:
    - known-sensitive keys get replaced with a shape descriptor (no key names)
    - any remaining raw strings get regex-scrubbed as a backstop
    - anything that is NOT a container, string or safe scalar is reduced to a
      type descriptor and never stringified

    F-13: this handled `dict` and `list` and let EVERYTHING else fall through to
    the formatter's `json.dumps(default=str)`. A pydantic/dataclass request model
    reached the logger as `str(model)`, so
    `{"req": RequestModel(prompt="…", token="sk-live-…")}` put the full prompt
    and bearer token into stdout with zero scrubbing. Tuples and sets had the
    same hole. Objects no longer reach `default=str` from here.
    """
    if isinstance(obj, dict):
        out: dict[str, Any] = {}
        for key, val in obj.items():
            # F-13 (round 7, Priya advisory 5): the KEY is data too. A dict keyed
            # by catalog entries — `{"catalog": {"Chanel No 5 (SKU 991)": 1}}` —
            # used to emit every key verbatim because only the VALUES were
            # scrubbed and `catalog` is not in _REDACT_KEYS. `shape_of` already
            # scrubs the keys it reveals; this is the same rule one level up.
            safe_key = scrub_text(str(key))
            if str(key).lower() in _REDACT_KEYS:
                out[safe_key] = shape_of(val, reveal_keys=False)
            else:
                out[safe_key] = _redact_for_log(val)
        return out
    if isinstance(obj, (list, tuple, set, frozenset)):
        return [_redact_for_log(item) for item in obj]
    if isinstance(obj, str):
        return scrub_text(obj)
    if obj is None or isinstance(obj, _SAFE_SCALARS):
        return obj
    if isinstance(obj, (bytes, bytearray)):
        return {"type": "bytes", "len": len(obj)}
    # Arbitrary object: describe it, never render it. If it exposes a mapping of
    # its own fields, recurse into that so the shape stays useful — every value
    # inside still goes through this same function.
    fields = getattr(obj, "__dict__", None)
    if isinstance(fields, dict) and fields:
        return {"type": type(obj).__name__, "fields": _redact_for_log(dict(fields))}
    return {"type": type(obj).__name__}


class RedactionJsonFormatter(logging.Formatter):
    """Emits one JSON object per log line, redacted, with correlation keys."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": scrub_text(record.getMessage()),
            "prompt_version": getattr(record, "prompt_version", PROMPT_VERSION),
            "workspace_id": getattr(record, "workspace_id", None),
            "request_id": getattr(record, "request_id", None),
        }
        extra_fields = getattr(record, "fields", None)
        if extra_fields:
            payload["fields"] = _redact_for_log(extra_fields)
        if record.exc_info:
            payload["exc_info"] = scrub_text(self.formatException(record.exc_info))
        return json.dumps(payload, default=str)


def configure_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    root.setLevel(level)
    # Avoid duplicate handlers on reload.
    root.handlers = []
    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(RedactionJsonFormatter())
    root.addHandler(handler)


def log_event(
    logger: logging.Logger,
    level: int,
    message: str,
    *,
    workspace_id: str | None = None,
    request_id: str | None = None,
    prompt_version: str = PROMPT_VERSION,
    fields: dict[str, Any] | None = None,
) -> None:
    """Preferred logging entry point across the service — forces callers to pass
    shapes/ids rather than raw payloads via the `fields` dict (which is itself
    redacted again as a backstop in the formatter).
    """
    logger.log(
        level,
        message,
        extra={
            "workspace_id": workspace_id,
            "request_id": request_id,
            "prompt_version": prompt_version,
            "fields": fields or {},
        },
    )
