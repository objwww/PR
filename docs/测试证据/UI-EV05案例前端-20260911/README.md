# UI-EV05 案例前端取证（2026-09-11）

方法：Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678，1366×768。脚本 `ev05-case-screenshot-cdp.mjs` 可复跑。

## 帧清单与主张

| 帧 | 主张 | 脚本控制台佐证 |
|---|---|---|
| s01-run-detail-cases-summary-unavailable.png | 证据汇总区诚实空态："证据汇总依赖后端 EV-05（evidence-summary 接口），当前未部署；不展示推测计数。" | `summaryEmpty` 原文一致 |
| s02-case-detail-button-disabled-no-caseid.png | 195 旧契约案例列表无 caseExecutionId → 行内"详情"按钮禁用+title 注明原因 | `detailBtnStates: disabled=true, title="该案例缺少 caseExecutionId，无法定位案例详情"` |
| s04-evidence-summary-refresh-still-honest.png | 点"刷新"后仍是同一诚实空态（不成错误重试环、不伪造计数） | 刷新后 description 原文一致 |

## 未取证的帧（诚实登记）

- **案例详情抽屉降级提示态**：脚本注入式复现（给列表行注入占位 caseExecutionId 使按钮可点、详情请求真打后端 403）两次尝试均不可靠（注入后列表刷新为空/按钮未如预期使能），主会话决定不交付该帧、不留存疑似帧。抽屉 403/404 → "案例详情依赖后端 EV-05，当前未部署"降级路径为**代码核验**（EvalRunDetailView.vue 详情状态机 st===403||404→unavailable），留合并部署窗真值回归。
- 本目录曾有 s01~s03 三帧 md5 全同的执行者初版产物（单帧复用），已被主会话剔除并重取 s01/s02/s04（md5 两两不同）。

## 部署

195 web 容器已含本批前端（截图即经隧道对 195 真环境取得）。
