# Influora API (Spring Boot 3.3.5 / Java 21) — multi-stage build.
#
# Per wiki/admin-progress/STAGING-DEPLOY-CHECKLIST.md §5 (Meera): this was the missing artifact
# blocking a real staging build/deploy pipeline for the Spring Boot service (previously only
# runnable via `mvn spring-boot:run` on a dev machine). Mirrors influora-ai/Dockerfile's
# conventions (non-root user, healthcheck, minimal runtime base) without copying its
# Python-specific mechanics — this uses Spring Boot's own layertools split instead.
#
# Stage 1 compiles the app with Maven. The build base is the official `maven` image (Maven +
# Temurin JDK 21 preinstalled) — a plain `eclipse-temurin` JDK image ships NO `mvn` binary, so
# the `RUN mvn ...` steps below would fail with "mvn: not found". This mirrors the exact command
# the staging checklist documents: `mvn clean package`.
# Stage 2 runs the extracted layers on a minimal, non-root JRE image. Splitting into layers
# (dependencies / spring-boot-loader / snapshot-dependencies / application, in that order, least-
# to-most-frequently-changing) means a rebuild after an application-only code change only
# invalidates the last, smallest layer — not a re-pull of the full dependency tree.

# ---- Build stage --------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Resolve dependencies before copying source so this layer is cached across rebuilds that only
# touch application code (pom.xml changes far less often than src/).
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests clean package \
    && java -Djarmode=layertools -jar target/*.jar extract --destination /workspace/extracted

# ---- Runtime stage -------------------------------------------------------------------------
# JRE-only (no JDK/compiler) + Alpine keeps the shipped image small; Temurin publishes official
# Alpine JRE variants so this isn't an unsupported combination.
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S influora && adduser -S influora -G influora -H -h /app
WORKDIR /app

# Each COPY is its own layer, ordered least-to-most-frequently-changing (matches the extraction
# order above) for the same rebuild-caching reason.
COPY --from=build --chown=influora:influora /workspace/extracted/dependencies/ ./
COPY --from=build --chown=influora:influora /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=influora:influora /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=influora:influora /workspace/extracted/application/ ./

USER influora

# Matches application.yml's `server.port: ${SERVER_PORT:8080}` default — override SERVER_PORT
# at `docker run`/compose time if this ever needs to bind elsewhere, and update this EXPOSE to
# match (EXPOSE is documentation only, it does not itself publish the port).
EXPOSE 8080

# GET /health is explicitly permitAll in SecurityConfig (no auth token needed). Full path
# includes server.servlet.context-path=/api/v1. start-period is generous because Flyway runs
# 39+ sequential migrations on boot before the app is ready to serve traffic.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:8080/api/v1/health || exit 1

# Spring Boot 3.2+ moved the launcher package from org.springframework.boot.loader to
# org.springframework.boot.loader.launch — 3.3.5 (this project's parent version) uses the new
# package.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
