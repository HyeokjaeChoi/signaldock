# Build context is the repository root (docker-compose.yml sets it).
# The server build reads ../gradle/libs.versions.toml and ../../generated/kotlin.
FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:57424b1d8eca9c4cf8a4fe226207027911f08d6758628891bf59fcfb53777029 AS build
WORKDIR /src
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY generated/kotlin generated/kotlin
COPY server server
WORKDIR /src/server
RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13
COPY --from=build /src/server/app/build/install/app /opt/signaldock
# The jre-noble base ships libbrotli1 1.1.0-2build2 (CVE-2026-111497, HIGH, fixed in 1.1.0-2ubuntu0.1); it failed the
# Trivy gate in demo-deploy run 40896676846. Remove this layer when the base image has the fixed package.
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade libbrotli1 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home signaldock
USER signaldock
# Inside the container network the server must listen on all interfaces.
# Compose does not publish the port beyond 127.0.0.1 on the host.
ENV SIGNALDOCK_BIND_HOST=0.0.0.0 PORT=8080
EXPOSE 8080
ENTRYPOINT ["/opt/signaldock/bin/app"]
