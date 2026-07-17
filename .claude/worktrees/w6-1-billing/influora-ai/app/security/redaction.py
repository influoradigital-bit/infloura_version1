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
# Phone numbers: optional +country code, 10-15 digits, allow separators
_PHONE_RE = re.compile(r"(?<!\d)(?:\+?\d{1,3}[-.\s]?)?\d{10}(?!\d)")
# Bank account / IBAN-ish: 9-18 digit runs (typical Indian bank account length)
_BANK_ACCOUNT_RE = re.compile(r"\b\d{9,18}\b")
# Generic secret-looking tokens: sk-..., Bearer <jwt>, long base64/hex blobs
_SECRET_RE = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9\-_.]{20,}|[A-Za-z0-9+/]{40,}={0,2})"
)
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
}


def scrub_text(value: str) -> str:
    """Regex backstop scrub for any raw string that reaches the logger."""
    value = _SECRET_RE.sub("[REDACTED_SECRET]", value)
    value = _PAN_RE.sub("[REDACTED_PAN]", value)
    value = _EMAIL_RE.sub("[REDACTED_EMAIL]", value)
    value = _PHONE_RE.sub("[REDACTED_PHONE]", value)
    value = _BANK_ACCOUNT_RE.sub("[REDACTED_ACCOUNT]", value)
    return value


def shape_of(value: Any) -> Any:
    """Convert a value into a safe 'shape' descriptor: lengths/counts/types, never
    the raw value itself. Use this instead of logging payloads directly.
    """
    if value is None:
        return None
    if isinstance(value, str):
        return {"type": "str", "len": len(value)}
    if isinstance(value, (bytes, bytearray)):
        return {"type": "bytes", "len": len(value)}
    if isinstance(value, list):
        return {"type": "list", "count": len(value)}
    if isinstance(value, dict):
        return {"type": "dict", "keys": sorted(value.keys())}
    if isinstance(value, (int, float, bool)):
        return value
    return {"type": type(value).__name__}


def _redact_for_log(obj: Any) -> Any:
    """Recursively redact a structure before it is serialized into a log line:
    - known-sensitive keys get replaced with a shape descriptor
    - any remaining raw strings get regex-scrubbed as a backstop
    """
    if isinstance(obj, dict):
        out: dict[str, Any] = {}
        for key, val in obj.items():
            if key.lower() in _REDACT_KEYS:
                out[key] = shape_of(val)
            else:
                out[key] = _redact_for_log(val)
        return out
    if isinstance(obj, list):
        return [_redact_for_log(item) for item in obj]
    if isinstance(obj, str):
        return scrub_text(obj)
    return obj


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
