"""The Reel safe zones, from config (spec v2 2026-09-26, section 2.6).

The safe zone is the part of a 9:16 Reel frame that the app's own top bar, buttons and caption
do NOT cover. The numbers are config, not code: `safe_zones.json` next to this file is canonical,
and `src/lib/shoot-check/safe-zones.json` (read by the app's `safe-zones.ts`) is its twin -- a
pytest reads both and fails on any difference, so Meera's quick checks and every guide the app
draws use the same zones.

Every value is a share 0..1 of the 9:16 frame (x to the right, y down): `top` is the covered top
bar; captions and all main text end above `caption_line`; the band from `caption_line` to
`covered_from` is for a short CTA on the left only (up to `cta_x_to`); everything below
`covered_from` is covered; `side` is kept clear on both sides; the rail is the right-side button
column (x from `rail_x`, y from `rail_y_from` to `rail_y_to`).

Validation matches the app's `parseSafeZonesConfig`: only the platform's `default` is read (a
device class is used only once measured, and none is yet); every value must be a finite number
in [0, 1] (a bool is not a number); `top < caption_line < covered_from`, `side < rail_x`, and
`rail_y_from < rail_y_to <= covered_from`. Anything else is never half-used: the loader returns
`INTERIM_SAFE_ZONES` and logs ONE error.
"""

from __future__ import annotations

import json
import logging
import math
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

SAFE_ZONES_PATH = Path(__file__).parent / "safe_zones.json"

PLATFORMS: tuple[str, ...] = ("instagram_reels", "youtube_shorts", "tiktok")
# Launch uses Instagram Reels only (the copy names Instagram); no platform picker exists (Q17).
DEFAULT_PLATFORM = "instagram_reels"


@dataclass(frozen=True)
class SafeZones:
    """One platform's zones, as shares 0..1 of the 9:16 frame."""

    status: str
    source: str
    top: float
    caption_line: float
    covered_from: float
    side: float
    rail_x: float
    rail_y_from: float
    rail_y_to: float
    cta_x_to: float


# The interim, unmeasured values (spec 2.6 table). Used whenever the config is unusable; the
# measurement task (M) replaces the JSON, never these, so a broken file still gives sane checks.
INTERIM_SAFE_ZONES = SafeZones(
    status="interim_unmeasured",
    source="interim 2026-09-26",
    top=0.14,
    caption_line=0.65,
    covered_from=0.78,
    side=0.04,
    rail_x=0.86,
    rail_y_from=0.50,
    rail_y_to=0.78,
    cta_x_to=0.55,
)


def _share(value: Any) -> float | None:
    """A finite number in [0, 1], else None. A bool, a string, null, NaN and Infinity all fail
    (`json.loads` accepts NaN and Infinity, so this checks `math.isfinite`)."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except OverflowError:  # an int too large for a float
        return None
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        return None
    return number


def read_zones(entry: Any) -> SafeZones | None:
    """One platform's `default` entry -> SafeZones, or None when any rule in 2.6 fails."""
    if not isinstance(entry, dict):
        return None
    status, source = entry.get("status"), entry.get("source")
    rail, cta = entry.get("rail"), entry.get("cta_band")
    if not isinstance(status, str) or not isinstance(source, str):
        return None
    if not isinstance(rail, dict) or not isinstance(cta, dict):
        return None
    values = (
        _share(entry.get("top")),
        _share(entry.get("caption_line")),
        _share(entry.get("covered_from")),
        _share(entry.get("side")),
        _share(rail.get("x")),
        _share(rail.get("y_from")),
        _share(rail.get("y_to")),
        _share(cta.get("x_to")),
    )
    if any(v is None for v in values):
        return None
    top, caption_line, covered_from, side, rail_x, rail_y_from, rail_y_to, cta_x_to = values
    if not (top < caption_line < covered_from):
        return None
    if not side < rail_x:
        return None
    if not (rail_y_from < rail_y_to <= covered_from):
        return None
    return SafeZones(
        status=status, source=source, top=top, caption_line=caption_line,
        covered_from=covered_from, side=side, rail_x=rail_x, rail_y_from=rail_y_from,
        rail_y_to=rail_y_to, cta_x_to=cta_x_to,
    )


def parse_safe_zones_config(raw: Any, platform: str = DEFAULT_PLATFORM) -> SafeZones:
    """The zones for `platform` from a parsed config object (the shape of `safe_zones.json`).
    Invalid in any way -> `INTERIM_SAFE_ZONES` and one error log. Never raises."""
    platforms = raw.get("platforms") if isinstance(raw, dict) else None
    entry = platforms.get(platform) if isinstance(platforms, dict) else None
    zones = read_zones(entry.get("default")) if isinstance(entry, dict) else None
    if zones is not None:
        return zones
    logger.error("safe_zones_config_invalid platform=%s; using the interim values", platform)
    return INTERIM_SAFE_ZONES


def load_safe_zones(path: Path = SAFE_ZONES_PATH, platform: str = DEFAULT_PLATFORM) -> SafeZones:
    """Read and validate the config file. A missing, unreadable or non-JSON file (NaN and
    Infinity parse, then fail validation) -> `INTERIM_SAFE_ZONES` and one error log."""
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError, RecursionError):
        logger.error("safe_zones_config_unreadable platform=%s; using the interim values", platform)
        return INTERIM_SAFE_ZONES
    return parse_safe_zones_config(raw, platform)


@lru_cache(maxsize=len(PLATFORMS))
def get_safe_zones(platform: str = DEFAULT_PLATFORM) -> SafeZones:
    """The bundled config's zones for `platform`, read once per platform (so an invalid file
    logs its one error once, not on every check)."""
    return load_safe_zones(SAFE_ZONES_PATH, platform)
