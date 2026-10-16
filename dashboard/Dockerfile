# Build context is the repository root (docker-compose.yml sets it).
# The Dashboard imports ../generated/typescript and ../web-shared.
FROM node:24.21.0-alpine3.24@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
WORKDIR /repo/dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci
COPY generated /repo/generated
COPY web-shared /repo/web-shared
COPY dashboard ./
# Not a secret: the project time zone only sets period boundaries. The query key is never a build input.
ARG VITE_SIGNALDOCK_TIME_ZONE=Asia/Seoul
RUN npm run build

FROM nginx:1.30.5-alpine3.24@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
# The official image renders /etc/nginx/templates/*.template with the container
# environment at start, so SIGNALDOCK_QUERY_KEY exists only in the running container.
COPY dashboard/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /repo/dashboard/dist /usr/share/nginx/html
