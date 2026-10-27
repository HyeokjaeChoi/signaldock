# Build context is the repository root (docker-compose.yml sets it).
# The Dashboard imports ../generated/typescript and ../web-shared.
FROM node:24.22.1-alpine3.24@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS build
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
# curl and libcurl 8.22.0-r0 (CVE-2026-92392, fixed in 8.23.0-r0). Remove this layer when a base tag has
# brotli-libs 1.2.1-r0 or later and curl and libcurl 8.23.0-r0 or later.
USER root
RUN apk upgrade --no-cache brotli-libs curl libcurl
USER 101
COPY dashboard/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /repo/dashboard/dist /usr/share/nginx/html
