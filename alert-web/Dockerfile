# EX-C1：alert-web 部署面——node 构建 + nginx 托管（dist 静态 + /api 反代）。
# 镜像内构建（与 control-app「宿主 maven 预构建」不同：node 生态无既定宿主链，
# 且 docker 内构建可复现——npm ci 按 lock 精确安装）。
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 环境徽章（联调审计修复 2026-09-17）：构建期注入 VITE_ENV_LABEL，vite 编译进 dist；
# 缺省空 = 前端如实显示「未标记」（AppShell.vue / LoginView.vue 兜底分支）
ARG VITE_ENV_LABEL=
ENV VITE_ENV_LABEL=${VITE_ENV_LABEL}
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
