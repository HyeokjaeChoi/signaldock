# Build context is the repository root (docker-compose.yml sets it).
# The server build reads ../gradle/libs.versions.toml and ../../generated/kotlin.
FROM eclipse-temurin:21.0.13.1_2-jdk-noble@sha256:5f9489c60984e9715afba8e7da353e9d78d2a38c0517662f5cc29fc8094efe1f AS build
WORKDIR /src
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY generated/kotlin generated/kotlin
COPY server server
WORKDIR /src/server
RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:21.0.13.1_2-jre-noble@sha256:897ec01ae1350622de0d5393a48a4ffffee8fe1609f248f4389578b919eaf9f5
COPY --from=build /src/server/app/build/install/app /opt/signaldock
# The jre-noble base ships libbrotli1 1.1.0-2build2 (CVE-2026-111497, HIGH, fixed in 1.1.0-2ubuntu0.1); it failed the
# Trivy gate in demo-deploy run 40896676846. It also ships apt and libapt-pkg6.0t64 2.8.3 (CVE-2026-109518, HIGH, fixed in
# 2.8.4ubuntu0.1); the image-scan gate (ADR-0049 B4) found that. Remove this layer when the base image has the fixed packages.
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade libbrotli1 apt libapt-pkg6.0t64 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home signaldock
USER signaldock
# Inside the container network the server must listen on all interfaces.
# Compose does not publish the port beyond 127.0.0.1 on the host.
ENV SIGNALDOCK_BIND_HOST=0.0.0.0 PORT=8080
EXPOSE 8080
ENTRYPOINT ["/opt/signaldock/bin/app"]
