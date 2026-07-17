# Influora frontend (Vite 6 + React 19 SPA, TECH-STACK.md — react-router-dom v7, NOT Next.js)
# — multi-stage build.
#
# Per wiki/admin-progress/STAGING-DEPLOY-CHECKLIST.md §1/§5 (Meera): no Dockerfile existed for
# this service before this — README's "Docker" section was a generic unused 3-line stub that
# never accounted for the VITE_* build-time env split. This replaces that stub.
#
# Stage 1 builds the static `dist/` bundle with Node; stage 2 serves it with nginx.
#
# IMPORTANT: Vite inlines every `VITE_*` env var into the JS bundle at BUILD time, not at
# container-run time (see .env.local.example) — setting `VITE_API_BASE_URL` as a `docker run -e`
# var on the runtime image does nothing, because by then the bundle is already static files. The
# staging/prod values must be supplied as `--build-arg` at `docker build` time instead (defaults
# below match local dev / .env.local.example so an unparameterized build still works).

# ---- Build stage --------------------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

# package-lock.json is the lockfile CI already builds against (.github/workflows/lighthouse-
# meera.yml runs `npm ci`), so this Dockerfile follows the same convention even though a
# pnpm-lock.yaml also exists in the repo — one lockfile, one install command, matching CI.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG VITE_API_MODE=live
ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ARG VITE_MEERA_STREAM_URL=https://ai.influora.internal
ENV VITE_API_MODE=$VITE_API_MODE \
    VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_MEERA_STREAM_URL=$VITE_MEERA_STREAM_URL

RUN npm run build

# ---- Runtime stage -------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

# nginx:alpine's stock config already runs the master process as root (required to bind port 80)
# and worker processes as the unprivileged `nginx` user it ships with — no extra non-root user
# setup needed here, unlike the Java/Python services which start as root by default.
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:80/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
