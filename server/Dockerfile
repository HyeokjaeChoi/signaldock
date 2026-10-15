# Build context is the repository root (docker-compose.yml sets it).
# The server build reads ../gradle/libs.versions.toml and ../../generated/kotlin.
FROM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build
WORKDIR /src
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY generated/kotlin generated/kotlin
COPY server server
WORKDIR /src/server
RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7
COPY --from=build /src/server/app/build/install/app /opt/signaldock
RUN useradd --system --no-create-home signaldock
USER signaldock
# Inside the container network the server must listen on all interfaces.
# Compose does not publish the port beyond 127.0.0.1 on the host.
ENV SIGNALDOCK_BIND_HOST=0.0.0.0 PORT=8080
EXPOSE 8080
ENTRYPOINT ["/opt/signaldock/bin/app"]
