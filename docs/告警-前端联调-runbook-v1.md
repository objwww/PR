# 前端联调 runbook v1（EX-C1 · docker profile）

> 面向：前端 ↔ control-app 联调的三条通路（本机 dev / 195 真栈 web / 纯后端 curl）。
> 前置事实：control-app 查询/命令 Controller 全部 `@Profile("docker")`——**本地裸起
> Spring 不注册端点**，联调必须走 docker 面或 195。盘点基线：
> `docs/告警-前端联调-端点缺口盘点-v1.md`（27 端点判定 + P0~P5 排序）。

## 1. 通路 A：本机 vite dev + 195 后端（日常开发）

```bash
cd alert-web
npm install
npm run dev            # vite 默认 5173；/api 代理见 vite.config.js
```

- 代理：`/api → http://localhost:8080`，**无 rewrite**（control-app 路径自带
  `/api` 前缀；曾有 rewrite 剥前缀 = 全 404，EX-C3a 已删，勿回加）。
- 目标后端二选一：
  - 195 真栈经 SSH 隧道：`ssh -L 8080:127.0.0.1:8080 root@195`（control-app 仅
    绑 loopback，隧道是唯一通路）；
  - 本机 docker profile 起控制面（需 PG + .env 密钥组，见 deploy/README）。
- mock 开关：`VITE_USE_MOCK=false npm run dev` 关全部 mock（未去 mock 的域会打
  真请求 → 404 报错）；默认开——**mock 只在调用点显式传了 `mock:` 时短路**，
  已去 mock 的端点（EX-C1 面：/rca-runs、/cases*、run 详情+事件流）恒走真请求。

## 2. 通路 B：195 真栈 web 服务（EX-C1 起入库）

```bash
ssh -L 8090:127.0.0.1:8090 root@146.56.195.225   # web 仅绑 loopback
# 浏览器打开 http://127.0.0.1:8090
```

- compose 服务 `web`（nginx:1.27-alpine，`deploy/docker-compose.yml`）：托管
  vite dist + `/api` 反代 control-app（SSE 透传 `proxy_buffering off`）；SPA 深链
  回退 `try_files … /index.html`。
- 构建：`docker compose build web`（镜像内 node:22 `npm ci` + `vite build`，
  alert-web/Dockerfile）。
- 部署/升级：`docker compose up -d web`。

## 3. 鉴权与 CSRF（EX-C3a 会话面）

1. 浏览器首跳任意壳内路由 → 路由守卫重定向 `/login?redirect=…`。
2. `/login` 提交 → `POST /api/auth/login`（form-urlencoded；前一步已
   `GET /api/auth/csrf` 落 XSRF-TOKEN cookie，axios 写请求自动回带
   `X-XSRF-TOKEN`——同源面零手工）。
3. 会话 = HttpOnly JSESSIONID（8h）；任何 API 401 → **client.js 拦截器全局重定向
   登录页**（带回跳地址；登录页自身 401 不重定向，就地报「口令错误」）。
4. 命令面（claim/ack/resolve/assign）：409 修订冲突 / 422 非法迁移是**业务结局**，
   前端就地解析 `{ok,conflict,error,case}`（CasesView postCommand）。

## 4. EX-C1 后的去 mock 矩阵

| 域 | 状态 | 说明 |
| --- | --- | --- |
| RunsView `/api/rca-runs` | **真** | severity/owner 值级近似、nextCursor null 属后端开放项（C-18/O-5） |
| CasesView summary/list/detail | **真** | detail 的 evidence/claims 恒空（后端投影缺证据摘要面，盘点 P1 #5） |
| CasesView 命令 4 件套 | **真 POST** | 409/422 就地解析；expectedRevision 取行/详情投影 |
| RunDetailView 详情 | **真** | task.name 缺（DAG 节点名空白）、attempts 是计数——后端 P1 #3 |
| RunDetailView 事件流 | **真 REST+SSE** | 换票 30s 单次：ES onerror → 关流重新换票；resync → 全量重种子 |
| Alerts / IncidentDetail / History / Eval / Monitor / Overview 摘要 / Notifications | **mock**（后端零实现） | 调用形状不变，后端落码后删 `mock:` 参数即切换；EvalCases runId 已修硬编码 |
| RunDetail 命令（cancel/hint/feedback） | **示意** | 端点已备但 run 投影缺 revision 字段——接线即恒 409，待后端补投影（P1 #3 桶） |
| Overview onClaim | **mock** | 数据源本身是 mock（caseId 非 UUID），随 Overview 摘要 API（P1 #4）一并接 |

## 5. 纯后端 curl 面（无浏览器验证 API）

```bash
# 经隧道后本机直打（或 195 宿主 curl 127.0.0.1:8080）：
curl -c /tmp/j -i http://127.0.0.1:8080/api/auth/csrf
curl -b /tmp/j -c /tmp/j -i -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H "X-XSRF-TOKEN: $(grep XSRF /tmp/j | awk '{print $7}')" \
  --data-urlencode 'username=<AUTH_OPERATOR_USERNAME>' \
  --data-urlencode 'password=<…>'
curl -b /tmp/j -i http://127.0.0.1:8090/api/cases   # 经 web 反代面
```
