# src/mocks — 页面级 mock 数据约定

后端配套接口（`GET /rca-runs` 等）落码前，各页面数据走本目录的 mock 适配层；
`VITE_USE_MOCK=false` 且后端就绪后，经 `src/api/client.js` 的 vite proxy `/api` → control-app:8080 联调，mock 不再生效。

## 约定

- **每页一个文件**：按页面命名，如 `overview.js`（P1）、`alerts.js`（P2）、`runs.js`（P3）、
  `cases.js`（P4）、`eval.js`（P5）、`monitor.js`（P6）。详情子路由与同页共用文件。
- **导出与 API 契约同形的函数**：函数返回 `Promise`，resolve 出的数据结构必须与后端接口契约
  （响应体字段、枚举取值）完全一致；机器码字段（事件类型、状态枚举等）保持英文，与线框图 annot 口径一致。
- **使用方式**：view 组件通过 `src/api/client.js` 的 `api(path, { mock })` 调用——
  mock 模式下返回 `mock()` 的 Promise，联调模式走真实 HTTP，组件代码两种模式下不变。
- mock 数据只服务前端开发自测，不进入构建产物语义；不得在其中写后端不存在的能力。
