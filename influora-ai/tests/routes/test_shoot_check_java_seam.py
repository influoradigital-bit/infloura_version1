"""The Java proxy and this route must agree on the path and the field names.

Why this exists: Shoot Check Level 2 shipped with its Python route tested against a hand-built
multipart request, and a frontend that called the wrong server entirely. Each side passed its own
tests. The Java backend now proxies the request (`CreatorMeeraController#checkFrame` ->
`MeeraVoiceAiClient#checkFrame`), and this test reads THAT client's source — not a fixture written
by the same person who wrote the route — for the path it calls and the multipart names it sends,
and checks both against what `app/routes/shoot_check.py` actually reads.

It fails, rather than skips, when the Java source is missing: CI runs this suite inside a
full-repo checkout, and a vacuous pass is how a seam like this goes unnoticed.
"""

from __future__ import annotations

import re
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[3]
_JAVA_CLIENT = (
    _REPO_ROOT
    / "influora-api/src/main/java/com/influora/integration/ai/MeeraVoiceAiClient.java"
)
_PY_ROUTE = _REPO_ROOT / "influora-ai/app/routes/shoot_check.py"


def _java() -> str:
    assert _JAVA_CLIENT.exists(), f"Java client not found at {_JAVA_CLIENT}"
    return _JAVA_CLIENT.read_text(encoding="utf-8")


def _python_route() -> str:
    return _PY_ROUTE.read_text(encoding="utf-8")


def _java_frame_multipart_names() -> set[str]:
    source = _java()
    start = source.index("static byte[] buildFrameMultipartBody(")
    body = source[start:]
    # Only the frame builder, not transcribe's: stop at the next method or the class end.
    end = body.find("\n    }\n", 0)
    body = body[: end if end != -1 else len(body)]
    return set(re.findall(r'name=\\"([a-z_]+)\\"', body))


def test_the_java_client_calls_this_routes_exact_path():
    java_path = re.search(r'FRAME_CHECK_PATH\s*=\s*"([^"]+)"', _java())
    assert java_path, "MeeraVoiceAiClient no longer declares FRAME_CHECK_PATH"
    py_path = re.search(r'@router\.post\("([^"]+)"\)', _python_route())
    assert py_path, "shoot_check.py no longer declares its POST route"
    assert java_path.group(1) == py_path.group(1)


def test_every_field_java_sends_is_one_this_route_reads():
    sent = _java_frame_multipart_names()
    read = set(re.findall(r'form\.get\("([a-z_]+)"\)', _python_route()))
    assert sent, "could not find the multipart names in MeeraVoiceAiClient.buildFrameMultipartBody"
    # A name Java sends that Python never reads would silently bind to nothing.
    assert sent <= read, f"Java sends {sorted(sent - read)} which the Python route never reads"


def test_the_two_fields_the_route_cannot_work_without_are_sent():
    sent = _java_frame_multipart_names()
    assert {"workspace_id", "image"} <= sent, sent


_JAVA_CONTROLLER = _REPO_ROOT / "influora-api/src/main/java/com/influora/web/CreatorMeeraController.java"


def _method_body(source: str, signature: str) -> str:
    """The text of the method starting at `signature`, up to the end of that method."""
    start = source.index(signature)
    body = source[start:]
    end = body.find("\n    }\n")
    return body[: end if end != -1 else len(body)]


_JAVA_WRITER = _REPO_ROOT / "influora-api/src/main/java/com/influora/service/meera/PhotoCheckChatWriter.java"


def test_java_never_maps_the_frame_check_json_to_a_record():
    # The photo-check response grew fields (lang, retake, a step's label and a settings step's
    # parts, PROMPT_VERSION .25.4) with NO Java change, on the promise that Java never maps the
    # body to a record: new fields would be dropped silently. Since the photo check moved into
    # Meera's chat (2026-09-26), Spring reads a `"fallback": false` body as a JSON TREE to store the
    # chat pair and add `chat`, and returns every other body byte for byte. Both stay key-agnostic.
    assert _JAVA_CONTROLLER.exists(), f"Java controller not found at {_JAVA_CONTROLLER}"
    source = _JAVA_CONTROLLER.read_text(encoding="utf-8").replace("\r\n", "\n")
    controller = _method_body(source, "public ResponseEntity<?> checkFrame(")
    assert "voiceAiClient.checkFrameForCreator(" in controller
    assert "passThrough(" in controller, "checkFrame no longer passes the bodies it does not keep through"
    pass_through = _method_body(source, "private static ResponseEntity<?> passThrough(")
    assert ".body(result.jsonBytes())" in pass_through, "passThrough no longer returns the AI's bytes as they came"

    assert _JAVA_WRITER.exists(), f"Java writer not found at {_JAVA_WRITER}"
    writer = _JAVA_WRITER.read_text(encoding="utf-8").replace("\r\n", "\n")
    keep = _method_body(writer, "public static ObjectNode writableResult(")
    assert "readTree(result.jsonBytes())" in keep and "deepCopy()" in keep, "the kept body is no longer a JSON tree copy"
    assert "readValue" not in writer and "treeToValue" not in writer and "convertValue" not in writer, (
        "PhotoCheckChatWriter maps the frame-check JSON to a type; new fields would be dropped"
    )

    client = _method_body(_java().replace("\r\n", "\n"), "private FrameCheckResult checkFrame(")
    assert "new FrameCheckResult(true, response.body()," in client, "the client no longer returns the raw body"
    assert "objectMapper" not in client and "readValue" not in client and "readTree" not in client


def test_the_grounded_check_inputs_are_sent_and_read_by_their_exact_names():
    # Grounded photo check (2026-09-25, contract C): the planned set-up and the coach-question
    # answers. A misspelt part name on either side would bind to nothing and the check would
    # quietly run without them.
    sent = _java_frame_multipart_names()
    assert {"shot_context", "answers"} <= sent, sent
    read = set(re.findall(r'form\.get\("([a-z_]+)"\)', _python_route()))
    assert {"shot_context", "answers"} <= read, read
