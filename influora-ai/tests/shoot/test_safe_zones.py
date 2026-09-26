"""The Reel safe-zone config (spec v2 2026-09-26, section 2.6): the two twins agree, and the
loader never half-uses a bad file.

The parity test READS BOTH FILES (this repo has shipped gates that compared two empty strings):
`influora-ai/app/shoot/safe_zones.json` (canonical, read by the quick checks) and
`src/lib/shoot-check/safe-zones.json` (read by the app's `safe-zones.ts` for the Reel layout guide,
the CapCut PNG and the live camera). Falsified 2026-09-26 by changing one value in one twin.
"""

from __future__ import annotations

import json
import logging
import math
from pathlib import Path

import pytest

from app.shoot.safe_zones import (
    INTERIM_SAFE_ZONES,
    PLATFORMS,
    SAFE_ZONES_PATH,
    SafeZones,
    get_safe_zones,
    load_safe_zones,
    parse_safe_zones_config,
)

REPO = Path(__file__).resolve().parents[3]
TS_TWIN = REPO / "src" / "lib" / "shoot-check" / "safe-zones.json"

_ZONE_KEYS = {"status", "source", "top", "caption_line", "covered_from", "side", "rail", "cta_band"}


def _numbers(node, path=""):
    """Every number in a parsed config, keyed by its path, so a failure names the value."""
    if isinstance(node, dict):
        out = {}
        for key, value in node.items():
            out.update(_numbers(value, f"{path}.{key}" if path else key))
        return out
    if isinstance(node, (int, float)) and not isinstance(node, bool):
        return {path: node}
    return {}


def _valid_default() -> dict:
    return {
        "status": "interim_unmeasured", "source": "interim 2026-09-26",
        "top": 0.14, "caption_line": 0.65, "covered_from": 0.78, "side": 0.04,
        "rail": {"x": 0.86, "y_from": 0.50, "y_to": 0.78}, "cta_band": {"x_to": 0.55},
    }


def _config(**overrides) -> dict:
    entry = _valid_default()
    for key, value in overrides.items():
        if "." in key:
            outer, inner = key.split(".", 1)
            entry[outer] = {**entry[outer], inner: value}
        else:
            entry[key] = value
    return {"schema": 1, "platforms": {p: {"default": entry, "device_classes": {}} for p in PLATFORMS}}


# --- parity: both twins, every value -----------------------------------------------------------


def test_both_twins_exist_and_hold_every_platform():
    canonical = json.loads(SAFE_ZONES_PATH.read_text(encoding="utf-8"))
    twin = json.loads(TS_TWIN.read_text(encoding="utf-8"))
    for config in (canonical, twin):
        assert set(config["platforms"]) == set(PLATFORMS)
        for platform in PLATFORMS:
            assert set(config["platforms"][platform]["default"]) == _ZONE_KEYS, platform
    # Not vacuous: there are real numbers to compare (8 per platform).
    assert len(_numbers(canonical)) == 1 + 8 * len(PLATFORMS)


def test_the_two_twins_hold_identical_values():
    canonical = json.loads(SAFE_ZONES_PATH.read_text(encoding="utf-8"))
    twin = json.loads(TS_TWIN.read_text(encoding="utf-8"))
    a, b = _numbers(canonical), _numbers(twin)
    assert a, "the canonical safe-zone file has no numbers"
    differing = {k: (a.get(k), b.get(k)) for k in a.keys() | b.keys() if a.get(k) != b.get(k)}
    assert not differing, f"safe_zones.json and safe-zones.json differ: {differing}"
    # The strings (status, source) and the structure too: one truth for both sides.
    assert canonical == twin


def test_the_bundled_config_is_valid_and_is_what_the_checks_read(caplog):
    caplog.set_level(logging.ERROR, logger="app.shoot.safe_zones")
    raw = json.loads(SAFE_ZONES_PATH.read_text(encoding="utf-8"))
    for platform in PLATFORMS:
        zones = parse_safe_zones_config(raw, platform)
        entry = raw["platforms"][platform]["default"]
        assert zones.top == entry["top"] and zones.caption_line == entry["caption_line"]
        assert zones.covered_from == entry["covered_from"] and zones.side == entry["side"]
        assert zones.rail_x == entry["rail"]["x"] and zones.rail_y_to == entry["rail"]["y_to"]
        assert zones.cta_x_to == entry["cta_band"]["x_to"]
    assert not caplog.records
    assert get_safe_zones() == parse_safe_zones_config(raw, "instagram_reels")


def test_the_interim_constants_are_the_spec_table():
    # Spec 2.6 interim values; the fallback must still draw and check a sane zone.
    assert INTERIM_SAFE_ZONES == SafeZones(
        status="interim_unmeasured", source="interim 2026-09-26", top=0.14, caption_line=0.65,
        covered_from=0.78, side=0.04, rail_x=0.86, rail_y_from=0.50, rail_y_to=0.78, cta_x_to=0.55,
    )


# --- validation: invalid -> the interim constants and ONE error log ------------------------------


def test_a_valid_config_is_used_as_written():
    zones = parse_safe_zones_config(_config(caption_line=0.6, top=0.1))
    assert zones.caption_line == 0.6 and zones.top == 0.1
    assert zones != INTERIM_SAFE_ZONES


@pytest.mark.parametrize(
    "overrides",
    [
        {"top": 0.7},  # top > caption_line
        {"caption_line": 0.8},  # caption_line > covered_from
        {"top": 0.65},  # top == caption_line (must be strictly less)
        {"side": 0.9},  # side > rail.x
        {"rail.y_to": 0.9},  # rail ends below covered_from
        {"rail.y_from": 0.78},  # y_from == y_to
        {"covered_from": 1.2},  # above 1
        {"side": -0.01},  # below 0
        {"top": True},  # a bool is not a number
        {"top": "0.14"},  # nor is a string
        {"top": None},
        {"cta_band.x_to": math.nan},
        {"status": 1},
        {"rail": [0.86, 0.5, 0.78]},
    ],
)
def test_an_invalid_config_falls_back_with_one_error_log(overrides, caplog):
    caplog.set_level(logging.ERROR, logger="app.shoot.safe_zones")
    assert parse_safe_zones_config(_config(**overrides)) == INTERIM_SAFE_ZONES
    assert len([r for r in caplog.records if r.levelno == logging.ERROR]) == 1


@pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity", "1e400"])
def test_nan_and_infinity_in_the_file_are_rejected(tmp_path, literal, caplog):
    # json.loads accepts NaN / Infinity (and 1e400 -> inf): the loader must check isfinite.
    caplog.set_level(logging.ERROR, logger="app.shoot.safe_zones")
    text = json.dumps(_config()).replace('"caption_line": 0.65', f'"caption_line": {literal}', 1)
    assert literal in text
    path = tmp_path / "safe_zones.json"
    path.write_text(text, encoding="utf-8")
    assert load_safe_zones(path) == INTERIM_SAFE_ZONES
    assert len(caplog.records) == 1


@pytest.mark.parametrize("content", [None, "", "{not json", "[]", '{"platforms": {}}'])
def test_a_missing_or_broken_file_falls_back(tmp_path, content, caplog):
    caplog.set_level(logging.ERROR, logger="app.shoot.safe_zones")
    path = tmp_path / "safe_zones.json"
    if content is not None:
        path.write_text(content, encoding="utf-8")
    assert load_safe_zones(path) == INTERIM_SAFE_ZONES
    assert len(caplog.records) == 1


def test_an_unknown_platform_falls_back():
    assert parse_safe_zones_config(_config(), "snapchat") == INTERIM_SAFE_ZONES
