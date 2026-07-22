# Influora AI service (Meera reasoner) — non-root, read-only FS, egress
# restricted to approved LLM endpoints + Spring internal only (Kabir guardrail #6).
#
# Egress allow-list is enforced at the network/ingress-egress-policy layer
# (e.g. Kubernetes NetworkPolicy / cloud firewall rules), not inside this
# Dockerfile — this image only documents the intended allow-list in comments
# and runs as an unprivileged user with no writable filesystem beyond /tmp.
#
# Allowed egress (enforce at the network layer):
#   - api.anthropic.com (Claude)
#   - generativelanguage.googleapis.com (Gemini)
#   - api.sarvam.ai (Sarvam STT/TTS)
#   - Spring internal mesh host only (SPRING_INTERNAL_BASE_URL)
#   - Spring JWKS URL host only (SPRING_JWKS_URL)
#   - Brand-supplied site URLs for /analyze-site, but ONLY through ssrf_guard,
#     which itself blocks private/loopback/link-local/CGNAT ranges.

# Pinned to bookworm (Debian 12): the floating `-slim` tag moved to trixie (Debian 13), which
# renamed ttf-unifont/ttf-ubuntu-font-family → fonts-unifont, breaking Playwright's
# `install --with-deps chromium` apt step (Package '...' has no installation candidate).
FROM python:3.13-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN groupadd --system influora && useradd --system --gid influora --home-dir /app influora

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && python -m playwright install --with-deps chromium \
    && python -m playwright install-deps

COPY app ./app

# Read-only application filesystem at runtime; only /tmp is writable (Playwright
# and any scratch buffers land there, never on disk under /app).
RUN chown -R influora:influora /app
USER influora

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3).status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
