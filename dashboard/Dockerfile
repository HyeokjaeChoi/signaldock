# Build context is the repository root (docker-compose.yml sets it).
# The Dashboard imports ../generated/typescript and ../web-shared.
FROM node:24.22.3-alpine3.24@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
WORKDIR /repo/dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci
COPY generated /repo/generated
COPY web-shared /repo/web-shared
COPY dashboard ./
# Not a secret: the project time zone only sets period boundaries. The query key is never a build input.
ARG VITE_SIGNALDOCK_TIME_ZONE=Asia/Seoul
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine3.24@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
# Runs as user nginx (101) and listens on 8080 (ADR-0045). Like the official image, it renders /etc/nginx/templates/*.template with the container
# environment at start, so SIGNALDOCK_QUERY_KEY exists only in the running container.
# The newest 1.31 alpine3.24 tag (1.31.7) still ships brotli-libs 1.2.0-r1 (CVE-2026-111497, fixed in 1.2.1-r0) and
# curl and libcurl 8.22.0-r0 (CVE-2026-92392, fixed in 8.23.0-r0). The image-scan gate (ADR-0049 B4) found more:
# libexpat 2.9.0-r0 (fixed in 2.10.0-r0), libxslt 1.1.43-r3 (1.1.43-r4), musl and musl-utils 1.2.6-r2 (1.2.6-r3), and
# zlib 1.3.2-r0 (1.3.2-r1), all HIGH. Remove this layer when a base tag has the fixed versions.
USER root
RUN apk upgrade --no-cache brotli-libs curl libcurl libexpat libxslt musl musl-utils zlib
USER 101
COPY dashboard/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /repo/dashboard/dist /usr/share/nginx/html
