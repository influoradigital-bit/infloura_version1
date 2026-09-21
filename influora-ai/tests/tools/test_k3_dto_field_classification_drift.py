"""F-1771 (MEDIUM, latent -- Priya "Last call -- K-3" F2) -- Python<->Java drift check for
`_model_copy_of_tool_result`'s (`app/tools/loop.py`) brand-written/trusted classification.

The allow-lists in `loop.py` only ever classified TOP-LEVEL and per-deal keys. `quote` sat
in `_TRUSTED_KEYS_GET_BRIEF` as a container trusted WHOLE, so an unknown key one level
inside it (Kabir/Priya's probe: `quote.brand_budget_note`) rode along outside the wrapper --
nobody had written a rule for it, and a container trusted whole trusts everything inside it
by construction. That runtime gap is closed in `loop.py` by `_is_fully_trusted_quote`
(fails closed: an unrecognised key anywhere inside `quote` pulls the whole container into
the wrapper). This test is the OTHER half: it pins every field
`CreatorToolDtos.GetBriefResult` / `PackageQuote` / `QuoteLine` / `AddOnLine` /
`CheckDealRisksResult` / `RiskFlag` / `GetMyDealsResult` / `DealSummary` carries against an
explicit Python classification (imported TRUSTED tuples from `loop.py`, or a literal
brand-written/specially-handled set declared below), read from the ACTUAL Java source, the
same way `tests/prompt/test_creator_context_drift.py` already does for
`MeeraContextDtos.CreatorContextResponse`.

It fails when any `@JsonProperty` is neither on a Python TRUSTED list nor declared
brand-written (or, for `GetMyDealsResult.deals`, declared specially-handled) -- so a new
field lands on neither side, forcing someone to make the wrap-or-trust call explicitly,
instead of silently relying on a runtime fail-closed default nobody wrote a test for. It
deliberately FAILS (not skips) when the Java source cannot be found, matching
`test_creator_context_drift.py`'s reasoning: a vacuous pass is how the last drift went
unnoticed.

Falsify: add a field to `PackageQuote` in a SCRATCH COPY of this Java text (never the real
file -- influora-api/ is out of this task's scope) and point `_JAVA_DTO_CANDIDATES` at the
scratch copy; every one of the eight `test_*_fields_are_fully_classified` cases below stays
green except the PackageQuote one, which goes red on the new, unclassified field.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.tools.loop import (
    _TRUSTED_DEAL_FIELDS_GET_MY_DEALS,
    _TRUSTED_KEYS_ADD_ON,
    _TRUSTED_KEYS_CHECK_DEAL_RISKS,
    _TRUSTED_KEYS_GET_BRIEF,
    _TRUSTED_KEYS_GET_MY_DEALS,
    _TRUSTED_KEYS_QUOTE,
    _TRUSTED_KEYS_QUOTE_LINE,
)

_REPO_ROOT = Path(__file__).resolve().parents[3]
_JAVA_DTO_CANDIDATES = (
    _REPO_ROOT / "influora-api/src/main/java/com/influora/web/dto/meera/CreatorToolDtos.java",
)


def _java_source() -> str:
    for candidate in _JAVA_DTO_CANDIDATES:
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8")
    pytest.fail(
        "CreatorToolDtos.java not found at any of "
        + ", ".join(str(c) for c in _JAVA_DTO_CANDIDATES)
        + " -- this drift check must run inside a full-repo checkout; a skip here is "
        "exactly the vacuous pass that let a nested field go unclassified (F-1771)."
    )


def _java_record_fields(source: str, record_name: str) -> set[str]:
    match = re.search(rf"record\s+{record_name}\s*\((.*?)\)\s*\{{", source, re.DOTALL)
    assert match, f"{record_name} record not found in CreatorToolDtos.java"
    names = re.findall(r'@JsonProperty\("([a-z0-9_]+)"\)', match.group(1))
    assert names, f"no @JsonProperty names parsed off {record_name}"
    return set(names)


def _assert_fully_classified(
    java_fields: set[str], trusted: set[str], other: set[str], record_name: str
) -> None:
    """`other` is whatever Python names EXPLICITLY as not-trusted for this record -- wrapped
    whole (brand-written free text), or, for `GetMyDealsResult.deals`, split and classified
    per-element by a separate mechanism. Either way it must be named, not merely implied by
    "everything trusted doesn't list it"."""
    accounted = trusted | other
    unclassified = sorted(java_fields - accounted)
    stale = sorted(accounted - java_fields)
    assert not unclassified, (
        f"{record_name}: Java field(s) {unclassified} are on NEITHER a Python TRUSTED list "
        "nor declared brand-written/specially-handled here -- F-1771, name each one "
        "explicitly (trusted, because it is fixed vocabulary or Influora-computed; or "
        "brand-written, because it can carry brand text)."
    )
    assert not stale, (
        f"{record_name}: Python names field(s) {stale} that CreatorToolDtos.java no "
        "longer carries -- stale entries hide a real removal behind dead code."
    )


# Fields NOT on a `_TRUSTED_*` tuple in loop.py, declared here so the check above cannot
# pass merely because "not trusted" was left unstated. Each set is independent of the Java
# parse -- a real drift must change BOTH sides, or one of the two assertions above fires.
_BRAND_WRITTEN_GET_BRIEF = {"extraction", "flags"}
_BRAND_WRITTEN_CHECK_DEAL_RISKS = {"flags"}
# `deals` is neither trusted-whole nor wrapped-whole: `_model_copy_of_tool_result` splits it
# per element, classifying each deal's OWN fields against `_TRUSTED_DEAL_FIELDS_GET_MY_DEALS`
# (see the DealSummary case below) and, since F-1771(b), folding a non-list `deals` or a
# non-dict element into the wrapper instead of leaking it trusted. Declared here so the
# top-level GetMyDealsResult check does not flag it as unclassified.
_SPECIALLY_HANDLED_GET_MY_DEALS = {"deals"}
_BRAND_WRITTEN_DEAL_SUMMARY = {"brand_name", "campaign_title"}
# RiskFlag never gets a nested allow-list of its own: `flags` is not on
# `_TRUSTED_KEYS_CHECK_DEAL_RISKS` (nor named as get_brief's `_TRUSTED_KEYS_GET_BRIEF`), so
# the ENTIRE array -- and therefore every field on every RiskFlag inside it -- is wrapped
# whole regardless of shape. Every current field is listed here anyway so a NEW one is
# still a conscious, reviewed addition rather than a silent shape change nobody signed off.
_BRAND_WRITTEN_RISK_FLAG = {
    "code",
    "severity",
    "title",
    "detail",
    "cost",
    "action",
    "data",
    "dismissible",
}


def test_get_brief_result_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "GetBriefResult")
    _assert_fully_classified(
        java, set(_TRUSTED_KEYS_GET_BRIEF), _BRAND_WRITTEN_GET_BRIEF, "GetBriefResult"
    )


def test_check_deal_risks_result_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "CheckDealRisksResult")
    _assert_fully_classified(
        java,
        set(_TRUSTED_KEYS_CHECK_DEAL_RISKS),
        _BRAND_WRITTEN_CHECK_DEAL_RISKS,
        "CheckDealRisksResult",
    )


def test_get_my_deals_result_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "GetMyDealsResult")
    _assert_fully_classified(
        java,
        set(_TRUSTED_KEYS_GET_MY_DEALS),
        _SPECIALLY_HANDLED_GET_MY_DEALS,
        "GetMyDealsResult",
    )


def test_deal_summary_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "DealSummary")
    _assert_fully_classified(
        java,
        set(_TRUSTED_DEAL_FIELDS_GET_MY_DEALS),
        _BRAND_WRITTEN_DEAL_SUMMARY,
        "DealSummary",
    )


def test_risk_flag_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "RiskFlag")
    _assert_fully_classified(java, set(), _BRAND_WRITTEN_RISK_FLAG, "RiskFlag")


# ---- the nested, "trusted container" records (F-1771's actual gap) --------------------


def test_package_quote_fields_are_fully_classified():
    """The record at the heart of F-1771: `quote` is the one container
    `_model_copy_of_tool_result` ever places in `trusted` whole. If Java adds a field here
    this test goes red -- the runtime fail-closed check in `_is_fully_trusted_quote` means
    an unclassified field is still wrapped safely, but this test is what makes the drift
    VISIBLE instead of silently relying on that fallback forever."""
    java = _java_record_fields(_java_source(), "PackageQuote")
    _assert_fully_classified(java, set(_TRUSTED_KEYS_QUOTE), set(), "PackageQuote")


def test_quote_line_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "QuoteLine")
    _assert_fully_classified(java, set(_TRUSTED_KEYS_QUOTE_LINE), set(), "QuoteLine")


def test_add_on_line_fields_are_fully_classified():
    java = _java_record_fields(_java_source(), "AddOnLine")
    _assert_fully_classified(java, set(_TRUSTED_KEYS_ADD_ON), set(), "AddOnLine")
