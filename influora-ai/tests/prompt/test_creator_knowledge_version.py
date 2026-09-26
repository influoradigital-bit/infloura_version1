"""CREATOR_KNOWLEDGE_VERSION (Meera intelligence v1, slice 2, spec 8.3).

Stamped on every recorded recommendation so an outcome can be traced to the knowledge that
produced it. It is DERIVED from the rendered knowledge (see `knowledge_version`'s docstring for
why not a hand-bumped constant), so these tests pin the derivation rather than a literal:
- it is exactly the hash of what Meera reads today (block + every lookup topic);
- any change to a row, to the block, or to a lookup topic moves it;
- a CRLF checkout of the same data file does not;
- it fits the `knowledge_version` column (VARCHAR(32)).
"""

from __future__ import annotations

import copy
import re
from pathlib import Path

from app.prompt import content_knowledge as ck


def _version_of(rows):
    text = ck.render_knowledge_block(rows)
    lookup = {topic: ck.render_lookup_topic(rows, topic) for topic in ck.LOOKUP_TOPICS}
    return ck.knowledge_version(text, lookup)


def test_the_version_is_the_hash_of_what_meera_reads_today():
    assert ck.CREATOR_KNOWLEDGE_VERSION == ck.knowledge_version(ck.CREATOR_KNOWLEDGE_TEXT, ck.LOOKUP_TEXT)
    assert ck.CREATOR_KNOWLEDGE_VERSION == _version_of(ck.load_knowledge())


def test_it_fits_the_column_and_reads_as_a_knowledge_version():
    assert re.fullmatch(r"ck-[0-9a-f]{16}", ck.CREATOR_KNOWLEDGE_VERSION)
    assert len(ck.CREATOR_KNOWLEDGE_VERSION) <= 32  # CreatorRecommendationService.VERSION_MAX


def test_a_changed_row_moves_the_version():
    rows = copy.deepcopy(ck.CREATOR_KNOWLEDGE_ROWS)
    structure = next(r for r in rows if r["data_type"] == "storytelling_structure")
    structure["video_application"] += " (edited)"
    assert _version_of(rows) != ck.CREATOR_KNOWLEDGE_VERSION


def test_a_changed_lookup_topic_moves_the_version():
    lookup = dict(ck.LOOKUP_TEXT)
    topic = sorted(lookup)[0]
    lookup[topic] += "\n- one more note"
    assert ck.knowledge_version(ck.CREATOR_KNOWLEDGE_TEXT, lookup) != ck.CREATOR_KNOWLEDGE_VERSION
    renamed = {("x" + k if k == topic else k): v for k, v in ck.LOOKUP_TEXT.items()}
    assert ck.knowledge_version(ck.CREATOR_KNOWLEDGE_TEXT, renamed) != ck.CREATOR_KNOWLEDGE_VERSION


def test_a_changed_block_moves_the_version():
    assert ck.knowledge_version(ck.CREATOR_KNOWLEDGE_TEXT + " ", ck.LOOKUP_TEXT) != ck.CREATOR_KNOWLEDGE_VERSION


def test_a_crlf_checkout_of_the_same_file_keeps_the_version(tmp_path: Path):
    raw = ck.KNOWLEDGE_PATH.read_bytes().replace(b"\r\n", b"\n")
    crlf = tmp_path / "video_content_concepts.jsonl"
    crlf.write_bytes(raw.replace(b"\n", b"\r\n"))
    assert _version_of(ck.load_knowledge(crlf)) == ck.CREATOR_KNOWLEDGE_VERSION
