"""Transport-contract pin for the browser-direct SSE stream (2026-07-17 fix).

The frontend streams a Meera turn by POSTing to `/chat` with the scoped
stream token in the `Authorization` header and parsing the response body as
SSE (`src/hooks/useMeeraStream.ts`). The PREVIOUS client used `EventSource`,
which is hard-wired to HTTP GET -- and `/chat` has never had a GET route, so
every live-mode stream failed at connection time and silently degraded to the
non-streaming fallback.

These tests pin the routing facts both sides now agree on, straight off the
FastAPI routing table (no lifespan startup, so no secrets needed):

  * `/chat` accepts POST -- the fetch()-based client's method.
  * `/chat` does NOT accept GET -- an EventSource client can never work; if
    someone reintroduces one, this is the test that should make them stop.

If a GET stream route is ever added deliberately (e.g. a token-in-query
EventSource edge for proxies that buffer POST bodies), update BOTH this test
and `useMeeraStream.ts` together -- the client and route are one contract.
"""

from __future__ import annotations

from app.main import app


def _methods_for(path: str) -> set[str]:
    methods: set[str] = set()
    for route in app.routes:
        if getattr(route, "path", None) == path:
            methods |= set(getattr(route, "methods", set()) or set())
    return methods


def test_chat_route_exists_and_accepts_post():
    methods = _methods_for("/chat")
    assert methods, "/chat route is not registered at all"
    assert "POST" in methods


def test_chat_route_has_no_get_so_eventsource_clients_cannot_connect():
    assert "GET" not in _methods_for("/chat")
