"""Signed callback client — Python -> Spring `/internal/meera/*`.

Matches Spring's `InternalRequestVerifier` (§2.4 of the security spec) exactly:

    X-Meera-Signature = HMAC_SHA256(sharedInternalHmacKey,
                                     method + path + sha256(body) + timestamp + nonce)

Every internal call also carries:
- `X-Meera-Service-Token` — the short-lived (<=60s TTL, hard ceiling enforced
  by Spring's `InternalServiceTokenProperties.MAX_TTL_SECONDS`) service JWT,
  `iss=meera-python`, `aud=influora-internal`. Minted fresh per call by
  `app.auth.service_token_minter.mint_service_token()` — this client never
  accepts a caller-supplied token, so there is exactly one code path that can
  produce this header and it always respects the 60s ceiling.
- `X-Onbehalf-Authorization` — the original brand user JWT, forwarded so Spring
  re-authorizes the human before executing (Kabir guardrail #1)
- `Idempotency-Key` — for money/state tools: `tool_use.id` + `workspace_id`

No blind retry on money-tool forwards — Spring owns dedupe via the idempotency
key; a network-level retry here would be safe only because it carries the same
idempotency key, but per spec we do NOT retry write/commit forwards at all,
only read-tier calls get bounded retries.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import secrets
import time
from dataclasses import dataclass
from typing import Any

import httpx

from app.auth.service_token_minter import mint_service_token
from app.config import get_settings
from app.tools.schemas import IDEMPOTENT_REQUIRED_TOOLS

logger = logging.getLogger(__name__)


class SpringCallError(Exception):
    def __init__(self, status_code: int | None, code: str, message: str, body: Any = None):
        self.status_code = status_code
        self.code = code
        self.message = message
        self.body = body
        super().__init__(message)


@dataclass(frozen=True)
class SpringResponse:
    status_code: int
    data: Any
    raw: dict[str, Any]


def _sha256_hex(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def sign_request(method: str, path: str, body: bytes, *, hmac_key: str, timestamp: str, nonce: str) -> str:
    """Computes X-Meera-Signature = HMAC(key, method+path+sha256(body)+timestamp+nonce)."""
    body_hash = _sha256_hex(body)
    message = f"{method.upper()}{path}{body_hash}{timestamp}{nonce}"
    digest = hmac.new(hmac_key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256)
    return digest.hexdigest()


def idempotency_key_for(tool_use_id: str, workspace_id: str) -> str:
    """Stable idempotency key derived from Claude's tool_use.id + workspace_id —
    same tool_use.id always yields the same key, so a retried stream can never
    double-execute a money/state tool.
    """
    return f"{tool_use_id}:{workspace_id}"


class SpringInternalClient:
    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.spring_internal_base_url,
            timeout=httpx.Timeout(
                connect=settings.timeouts.spring_connect,
                read=settings.timeouts.spring_read,
                write=settings.timeouts.spring_read,
                pool=settings.timeouts.spring_connect,
            ),
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    def _build_signed_headers(
        self,
        *,
        method: str,
        path: str,
        body: bytes,
        onbehalf_jwt: str,
        idempotency_key: str | None,
    ) -> dict[str, str]:
        settings = self._settings
        timestamp = str(int(time.time()))
        nonce = secrets.token_hex(16)
        signature = sign_request(
            method, path, body, hmac_key=settings.internal_hmac_key, timestamp=timestamp, nonce=nonce
        )
        # Minted fresh for this single call -- never accepted from a caller, so
        # the 60s ceiling (InternalServiceTokenProperties.MAX_TTL_SECONDS) is
        # always what Spring sees, never a longer TTL some upstream caller
        # might otherwise have supplied.
        service_token = mint_service_token()
        headers = {
            "Content-Type": "application/json",
            "X-Meera-Service-Token": service_token,
            "X-Onbehalf-Authorization": onbehalf_jwt,
            "X-Meera-Signature": signature,
            "X-Meera-Timestamp": timestamp,
            "X-Meera-Nonce": nonce,
            "X-Meera-Key-Id": settings.internal_hmac_key_id,
        }
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        return headers

    async def call_tool_endpoint(
        self,
        *,
        tool_name: str,
        path: str,
        payload: dict[str, Any],
        onbehalf_jwt: str,
        idempotency_key: str | None,
        allow_retry: bool = False,
    ) -> SpringResponse:
        """POSTs a tool-call forward to Spring. `allow_retry` must only ever be
        True for read-tier tools — money/state (draft+commit tier) forwards are
        NEVER retried here (Spring owns dedupe via Idempotency-Key, but we still
        avoid blind retries entirely for those to keep the invariant obvious and
        testable at this layer too).

        The `X-Meera-Service-Token` is minted fresh per attempt (including
        retries) by `_build_signed_headers` — callers never supply one.
        """
        import json as _json

        body_bytes = _json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        settings = self._settings

        attempts = settings.retry.max_retries + 1 if allow_retry else 1
        last_exc: Exception | None = None

        for attempt in range(attempts):
            headers = self._build_signed_headers(
                method="POST",
                path=path,
                body=body_bytes,
                onbehalf_jwt=onbehalf_jwt,
                idempotency_key=idempotency_key,
            )
            try:
                response = await self._client.post(path, content=body_bytes, headers=headers)
            except httpx.HTTPError as exc:
                last_exc = exc
                if attempt < attempts - 1:
                    continue
                raise SpringCallError(None, "network_error", str(exc)) from exc

            try:
                parsed = response.json()
            except ValueError:
                parsed = {}

            if response.status_code >= 500 and attempt < attempts - 1 and allow_retry:
                continue

            if response.status_code >= 400:
                raise SpringCallError(
                    response.status_code,
                    parsed.get("error", {}).get("code", "spring_error") if isinstance(parsed, dict) else "spring_error",
                    parsed.get("error", {}).get("message", "internal call failed")
                    if isinstance(parsed, dict)
                    else "internal call failed",
                    body=parsed,
                )

            return SpringResponse(
                status_code=response.status_code,
                data=parsed.get("data") if isinstance(parsed, dict) else parsed,
                raw=parsed if isinstance(parsed, dict) else {},
            )

        # Should be unreachable, but keep mypy/logic happy.
        raise SpringCallError(None, "network_error", str(last_exc) if last_exc else "unknown error")

    async def persist_assistant_message(
        self,
        *,
        conversation_id: str,
        content: str,
        metadata: dict[str, Any],
        turn_id: str,
        onbehalf_jwt: str,
    ) -> SpringResponse:
        """POST /internal/meera/messages — turn-completion write-back, idempotent
        on turn_id. `turn_id` MUST be the stream token's own verified
        `messageId` claim (see `app/routes/chat.py`) -- never a client-supplied
        value; Spring's `AICreditService#wasCharged`/`#release` key off this
        SAME value, so a client-controlled turn_id here would reopen Kabir
        red-team FAIL 2 (constant turn_id across turns zero-charging
        everything after the first). The service token is minted internally
        by `call_tool_endpoint` — callers never supply one.
        """
        payload = {
            "conversationId": conversation_id,
            "role": "ASSISTANT",
            "content": content,
            "metadata": metadata,
        }
        return await self.call_tool_endpoint(
            tool_name="_persist_message",
            path="/internal/meera/messages",
            payload=payload,
            onbehalf_jwt=onbehalf_jwt,
            idempotency_key=turn_id,
            allow_retry=False,
        )

    async def persist_analyze_site_result(
        self,
        *,
        workspace_id: str,
        url: str,
        success: bool,
        data: dict[str, Any] | None,
        error: dict[str, Any] | None,
        onbehalf_jwt: str,
    ) -> SpringResponse:
        """POST /internal/meera/analyze_site_result -- write-back for the Meera CHAT tool loop's
        LOCAL `analyze_site` tool (see app/tools/loop.py -- `is_local_tool` runs
        `perform_site_analysis` in-process for a fast reply and, unlike every other tool, never
        forwards the result to Spring, so a chat-pasted URL never updated `BrandProfile` the way
        the form/onboarding path already does). `data`/`error` are passed straight through from
        `perform_site_analysis`'s own return shape -- Spring's `AnalyzeSiteAiDtos.Data` already
        deserializes that exact snake_case shape (source_url/niche_tags/tone_dial/brand_color/
        product_catalog), since it is the SAME function backing both call sites. No
        `Idempotency-Key` -- like `release_turn_credit`, this is a full-state upsert onto
        `BrandProfile`, naturally idempotent on repeat delivery, not an append.
        """
        payload = {
            "workspaceId": workspace_id,
            "url": url,
            "success": success,
            "data": data,
            "error": error,
        }
        return await self.call_tool_endpoint(
            tool_name="_analyze_site_result",
            path="/internal/meera/analyze_site_result",
            payload=payload,
            onbehalf_jwt=onbehalf_jwt,
            idempotency_key=None,
            allow_retry=False,
        )

    async def release_turn_credit(
        self,
        *,
        conversation_id: str,
        turn_id: str,
        onbehalf_jwt: str,
    ) -> SpringResponse:
        """POST /internal/meera/turns/release — refunds a turn's send-time
        charge after a genuine PROVIDER failure (Kabir red-team FAILs #1/#2
        fix; Wave 2 round 2). `chat.py` calls this ONLY on a real provider
        failure / empty reply, NEVER on a plain client disconnect -- the
        disconnect/provider-failure distinction lives entirely on the caller
        side, this client has no opinion on it.

        `turn_id` MUST be the same server-verified `messageId` claim used for
        `persist_assistant_message` above -- Spring's `AICreditService#release`
        is idempotent AND guarded keyed on exactly that value (won't refund a
        turn that wasn't charged, won't refund one whose reply already
        persisted). No `Idempotency-Key` header is sent: release is already
        self-idempotent server-side on `turn_id`, so `allow_retry` stays False
        purely to match the "no blind retries on money/state forwards"
        invariant documented at the top of this module, not because a retry
        here would be unsafe.
        """
        payload = {
            "conversationId": conversation_id,
            "turnId": turn_id,
        }
        return await self.call_tool_endpoint(
            tool_name="_release_turn_credit",
            path="/internal/meera/turns/release",
            payload=payload,
            onbehalf_jwt=onbehalf_jwt,
            idempotency_key=None,
            allow_retry=False,
        )
