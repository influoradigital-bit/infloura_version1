"""The knowledge row Meera quotes for Reel safe zones says the same numbers as the safe-zone
config the Reel layout guide draws (shoot guide spec v2, 2.6 "One truth for three places" and
Phase 4 "Knowledge fix", 2026-09-26).

The row (`platform_export_setting`, "Instagram Reels / YouTube Shorts") is always sent and also
reaches the frame check. It used to say "top 15% ... bottom 25%"; it now states the config's
values in words. This test READS app/shoot/safe_zones.json and reads the percentages back out of
the row's text, so changing a number on either side without the other goes red. It never
compares a value with itself: the row side is parsed from prose, the config side from JSON.
"""

from __future__ import annotations

import json
import math
import re
from pathlib import Path

import pytest

from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_ROWS, CREATOR_KNOWLEDGE_TEXT

SAFE_ZONES_PATH = Path(__file__).resolve().parents[2] / "app" / "shoot" / "safe_zones.json"
ROW_PLATFORM = "Instagram Reels / YouTube Shorts"
# The row names both apps; both configs must agree with it.
CONFIG_PLATFORMS = ("instagram_reels", "youtube_shorts")

# Each phrase of the row that carries a number -> the config key(s) it states, in order.
PHRASES: tuple[tuple[str, tuple[str, ...]], ...] = (
    (r"out of the top (\d+)% \(the app's top bar\)", ("top",)),
    (r"end above the (\d+)% line", ("caption_line",)),
    (r"From (\d+)% to (\d+)% of the height: a short CTA only, on the left side", ("caption_line", "covered_from")),
    (r"Below (\d+)% is covered", ("covered_from",)),
    (r"about (\d+)% in from each side", ("side",)),
)


def _row() -> dict:
    rows = [
        r
        for r in CREATOR_KNOWLEDGE_ROWS
        if r["data_type"] == "platform_export_setting" and r["platform"] == ROW_PLATFORM
    ]
    assert len(rows) == 1
    return rows[0]


def _config_default(platform: str) -> dict:
    config = json.loads(SAFE_ZONES_PATH.read_text(encoding="utf-8"))
    assert config["schema"] == 1
    return config["platforms"][platform]["default"]


def _stated_percentages(text: str) -> dict[str, list[int]]:
    """Config key -> every whole percentage the row states for it."""
    stated: dict[str, list[int]] = {}
    for pattern, keys in PHRASES:
        found = re.findall(pattern, text)
        assert len(found) == 1, (pattern, found)
        values = found[0] if isinstance(found[0], tuple) else (found[0],)
        for key, value in zip(keys, values, strict=True):
            stated.setdefault(key, []).append(int(value))
    return stated


def test_the_config_file_is_there_and_not_empty():
    assert SAFE_ZONES_PATH.is_file(), SAFE_ZONES_PATH
    for platform in CONFIG_PLATFORMS:
        zones = _config_default(platform)
        for key in ("top", "caption_line", "covered_from", "side"):
            assert isinstance(zones[key], (int, float)) and math.isfinite(zones[key]), key
            assert 0 < zones[key] < 1, key


@pytest.mark.parametrize("platform", CONFIG_PLATFORMS)
def test_the_row_states_the_config_percentages(platform):
    zones = _config_default(platform)
    stated = _stated_percentages(_row()["safe_zones"])
    assert set(stated) == {"top", "caption_line", "covered_from", "side"}
    for key, values in stated.items():
        for value in values:
            assert value == pytest.approx(zones[key] * 100, abs=1e-9), (key, value, zones[key])


def test_the_row_states_no_other_number():
    text = _row()["safe_zones"]
    every = [int(n) for n in re.findall(r"(\d+)%", text)]
    stated = _stated_percentages(text)
    assert sorted(every) == sorted(v for values in stated.values() for v in values)
    assert not re.search(r"\d(?!\d*%)", text), "a number that is not one of the stated percentages"


def test_the_row_says_the_rest_in_words_and_names_no_source():
    text = _row()["safe_zones"]
    for words in ("left side", "right-side buttons", "check your app preview"):
        assert words in text, words
    for banned in ("15%", "25%", "35%", "Meta", "Instagram's", "@", "source"):
        assert banned not in text, banned
    assert text in CREATOR_KNOWLEDGE_TEXT
