# EX-D1 值班通知群模拟——195 部署与验证证据（2026-09-10）

范围：`GET /api/duty/notifications/feed`（control-app 只读新端点）+ alert-web「群模拟」页
（/duty/chat）。无 flyway 迁移；只动 control-app / web 两容器。代码改动清单与本地验证见
`docs/告警-PROGRESS.md` EX-D1 两节。

## 文件清单

| 文件 | 内容 |
|---|---|
| `exd1-verify-195.log` | 195 真 PG `mvn -B -ntp verify -pl control-app -am` 全量输出（JAVA_HOME=/opt/jdk-21.0.12.1+1 固化做法） |
| `exd1-deploy-verify-195.log` | recreate + 四条验证脚本输出原文（V1/V2/V2b/V4 + 现场终态） |
| `dom-check-result.json` | DOM 级渲染断言结果（Edge headless + CDP，全项 true） |
| `dom-render.html` | `.chat-body` 序列化 DOM 原文 |
| `dom-screenshot.png` | 页面截图（气泡/卡片/三色/机器人标签/时间分隔条/演练通道可视实证） |
| `dom-stub-serve.mjs` / `dom-check-cdp.mjs` | DOM 检查脚手架（dist 静态 + feed 契约 stub；CDP 预置会话标记） |

## 构建与 IT 数字（195 真机）

- surefire：**1087 测 / 0F / 0E / 0 skipped**（真机零跳过，含新增
  `DutyQueryControllerTest.feedCarriesBubbleShapeAndRealDeliveryState`）
- failsafe（真 PG）：**178 测 / 0F / 0E / 0 skipped**，含
  `PostgresDutyStoreIT` **5/5**（新增 `feedCarriesBodyAndRealDeliveryRows` 真库实跑）
- BUILD SUCCESS；`docker compose build control-app web` exit=0

## 四条验证结论

1. **群模拟页见历史气泡+演练通道标记：PASS**——web `/duty/chat` SPA 200；
   feed limit=20 中 `"drill":true` 命中 2 条（EX-C2 演练期 echo-bot SENT 行）；
   页面截图（DOM 检查面）气泡/卡片/分隔条全渲染。
2. **新通知 30s 级出现 + delivery 状态如实演进：PASS（以 RCA_SYSTEM 全链替代
   MANUAL 入口——见下注）**——AM webhook(cr 线) 202 accepted +
   dutyNotificationId；11:01:33 首行 `DEAD@rl-bot`（lastError 如实=
   channel_not_configured「webhook 未注入」）→ 11:02:09 补投行 `SENT@echo-bot`
   drill:true sentAt=11:01:59Z（真实降级链演进 29s）。GATUS 回写 created:true →
   feed 行 `deliveries:[]`（127 直发回写标注面）。
   注：MANUAL 入口 `POST /api/duty/test-notification` 的写面主人=浏览器会话+CSRF
   （EX-C3a 矩阵），operator 口令原文按 EX-C3a 纪律零落盘、本执行者不可得，
   故用真实 AM 链路验证等价语义（气泡出现+状态演进）；MANUAL 入口本身有 UT 锚定。
3. **DOM 级渲染：PASS**——`dom-check-result.json` 全项 true（群名栏/圆头像/
   PR 告警中心昵称/机器人标签/时间分隔条/卡片骨架/标题加粗/引用灰竖条/
   fc-warning 橙红/fc-comment 灰/粗体/@zhangwei 蓝色/跳转行恰 1 条（仅
   RCA_SYSTEM）/演练通道/已送达/失败/127 直发 chips）；截图 `dom-screenshot.png`。
   数据面为契约 stub（真实栈数据由 2 的 feed JSON 实证），组件渲染为真。
4. **认证面：PASS**——未认证 `feed` 直打 8080=401、经 web 8090=401、
   op 线 bearer=200（并入既有 /api/duty/** 面，未开洞）。

## 现场终态

- `deploy-control-app-1` / `deploy-web-1` Up（recreate 后 54s），其余 30+ 容器
  全部 Up 未动（notify-app/postgres/alertmanager/prometheus/litellm 等）
- 内存 available 3358M（7725 总量）；磁盘 / 62%（22G 余）
- 远端临时文件已清（/tmp/exd1-*）
