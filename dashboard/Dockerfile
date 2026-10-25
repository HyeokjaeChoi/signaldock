# Build context is the repository root (docker-compose.yml sets it).
# The Dashboard imports ../generated/typescript and ../web-shared.
FROM node:24.22.0-alpine3.24@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS build
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
COPY dashboard/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /repo/dashboard/dist /usr/share/nginx/html
