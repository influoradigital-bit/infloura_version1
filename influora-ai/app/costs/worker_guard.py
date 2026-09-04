"""Gate fix round 2 (Q7, Priya caveat 1) -- the per-creator monthly cap's
concurrency guarantee has a SCOPE, and this module makes that scope explicit
at boot and on `/readyz` instead of leaving it to an ops invariant nobody
asserted.

The invariant:

* With `REDIS_URL` configured, the creator hold is a shared atomic counter
  (`app.costs.spend_tracker`, `:held` key) and the cap survives any number of
  uvicorn workers or container replicas.
* Without `REDIS_URL`, holds AND totals are per-process. That is fine for a
  single process (dev, single-instance) and silently wrong for more than one:
  two workers at cap-minus-one both admit a turn and the creator overspends.

So: more than one uvicorn worker with no Redis is a boot-time misconfiguration
and the service refuses to start (same shape as the missing-secrets refusal in
`app.main`). Replica count is not visible from inside a container, so
`/readyz` additionally reports `creator_cap_scope` (`"shared"` /
`"per_process"`) for the deploy to check.

Worker count detection: uvicorn takes `--workers N` / `--workers=N` on its
command line or `WEB_CONCURRENCY` in the environment (CLI wins, matching
uvicorn's own precedence). Spawned worker processes inherit the parent's
`sys.argv` via multiprocessing's spawn bootstrap, so the flag is visible from
inside each worker too. `-w` is uvicorn's short form.
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass

CREATOR_CAP_SCOPE_SHARED = "shared"
CREATOR_CAP_SCOPE_PER_PROCESS = "per_process"


def configured_worker_count(argv: list[str] | None = None, environ: dict | None = None) -> int:
    """How many uvicorn workers this deployment asked for (1 when unspecified
    or unparseable -- an unparseable value means uvicorn itself would have
    refused to start)."""
    args = list(sys.argv if argv is None else argv)
    env = os.environ if environ is None else environ
    for index, arg in enumerate(args):
        for flag in ("--workers", "-w"):
            if arg == flag and index + 1 < len(args):
                return _parse_positive_int(args[index + 1])
            if arg.startswith(flag + "="):
                return _parse_positive_int(arg.split("=", 1)[1])
    web_concurrency = env.get("WEB_CONCURRENCY")
    if web_concurrency:
        return _parse_positive_int(web_concurrency)
    return 1


def _parse_positive_int(raw: str) -> int:
    try:
        value = int(str(raw).strip())
    except (TypeError, ValueError):
        return 1
    return value if value > 0 else 1


@dataclass(frozen=True)
class CreatorCapScope:
    workers: int
    redis_configured: bool

    @property
    def scope(self) -> str:
        return (
            CREATOR_CAP_SCOPE_SHARED if self.redis_configured else CREATOR_CAP_SCOPE_PER_PROCESS
        )

    @property
    def safe(self) -> bool:
        """False exactly when the cap would be silently voided: more than one
        worker in this process tree with no shared store."""
        return self.redis_configured or self.workers <= 1

    def boot_error(self) -> str | None:
        if self.safe:
            return None
        return (
            f"refusing to boot: {self.workers} uvicorn workers requested but REDIS_URL is "
            "unset. The per-creator monthly AI cap (AI_CREATOR_MONTHLY_CAP_USD) and the daily "
            "spend ceiling keep their holds and totals per-process without Redis, so a "
            "multi-worker deploy would let concurrent turns overspend silently. Set "
            "REDIS_URL, or run with --workers 1."
        )


def creator_cap_scope(
    *, redis_configured: bool, argv: list[str] | None = None, environ: dict | None = None
) -> CreatorCapScope:
    return CreatorCapScope(
        workers=configured_worker_count(argv, environ), redis_configured=redis_configured
    )
