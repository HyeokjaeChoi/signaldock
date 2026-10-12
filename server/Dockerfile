# Build context is the repository root (docker-compose.yml sets it).
# The server build reads ../gradle/libs.versions.toml and ../../generated/kotlin.
FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS build
WORKDIR /src
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY generated/kotlin generated/kotlin
COPY server server
WORKDIR /src/server
RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c
COPY --from=build /src/server/app/build/install/app /opt/signaldock
RUN useradd --system --no-create-home signaldock
USER signaldock
# Inside the container network the server must listen on all interfaces.
# Compose does not publish the port beyond 127.0.0.1 on the host.
ENV SIGNALDOCK_BIND_HOST=0.0.0.0 PORT=8080
EXPOSE 8080
ENTRYPOINT ["/opt/signaldock/bin/app"]
