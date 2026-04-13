FROM node:20-bookworm-slim AS build

WORKDIR /app

COPY package*.json ./
RUN unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy; \
	npm config delete proxy >/dev/null 2>&1 || true; \
	npm config delete https-proxy >/dev/null 2>&1 || true; \
	npm install --include=dev --no-audit --no-fund --registry=https://registry.npmjs.org

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]