# UI-UX02 前端（值班仿真机器人对话窗）取证 · 2026-09-11

> 复用 UI-UX01 方法：SSH 隧道 + Edge headless + CDP + 真登录 test/12345678，1366×768。
> 前置：`ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225`
> 复跑：`node ux02-screenshot-cdp.mjs`

## 背景

- 后端 UX-02（/api/v1/duty-bot/**）在 ev/eval-center 隔离分支完成，**未部署**；
  195 已部署后端无此接口——脚本实测 `GET /api/v1/duty-bot/sessions` = **403**。
- 前端必须诚实降级：显式横幅 + 输入禁用，不伪造机器人回复。

## 截图清单

| 文件 | 内容 | 断言（脚本 stdout） |
|---|---|---|
| s01-duty-collapsed-entry.png | 值班页对话窗**收起态**：右上「仿真机器人」入口 + 橙色「仿真」标记，值班表主体零挤压 | `entryBtn:["仿真机器人"]`，`simTag` 含「仿真」 |
| s02-drawer-expanded-not-deployed-banner.png | 抽屉**展开态**：顶部横幅「机器人接口依赖后端 UX-02，当前未部署」+ 说明行；会话列表/新建会话按钮均禁用 | `banner:"机器人接口依赖后端 UX-02，当前未部署"`，`sessionBarBtnsDisabled:[true,true]` |
| s03-input-disabled.png | **输入禁用态**：textarea disabled（placeholder="机器人不可用"）、发送按钮禁用、字符计数 0/400 | `textareaDisabled:true`，`sendDisabled:[true]` |

## 部署验证

- `npm run build` 绿（vite 7.3.6，2493 modules，10.18s）。
- tar 同步（排除 node_modules/dist）→ 195 `/opt/build/pr/alert-web` 先清空再解包；
  `cd /opt/build/pr/deploy && docker compose build web && docker compose up -d web`（只动 web 容器）。
- 195 宿主 `curl http://127.0.0.1:8090/` = **200**；`deploy-web-1 Up`。

## 功能面（按契约实现，195 上因后端未部署不可真验的部分）

- 会话列表（键集游标分页 + 加载更多）、新建会话、消息流（seq 游标，续页"加载更早消息"）。
- 用户右/机器人左气泡 + 时间；references_json 中 `type=incident` 渲染为 `/alerts/{id}` 跳转链接，
  其余类型（notify_outbox）随行标注不伪造跳转。
- 幂等：clientMessageId=crypto.randomUUID()（带降级 fallback）；发送中按钮禁用防重复；
  失败消息标「发送失败 · 重试」，重试沿用同一 clientMessageId（后端幂等锚去重，replayed=true 原样返回）。
- 错误码：400 → 就地提示（最长 400 字符）；404/403 → apiDown 横幅 + 输入禁用；
  409 → 「本会话已达上限，请新建会话」；429 → 「消息太频繁，请稍后再试」。
