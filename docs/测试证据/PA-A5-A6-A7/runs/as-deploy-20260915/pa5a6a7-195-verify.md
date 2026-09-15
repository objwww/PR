# PA-A5/A6/A7 195 部署与真机验证台账（2026-09-15）

批次：Phase A 收官三片（A5 决策溯源统一字段族 / A6 OTel Zipkin 导出接线 / A7 webhook HMAC-SHA256 双因子）——矩阵 L8-5 / L8-6 / L0-1 ⚠️ 全部收口
部署机：195（146.56.195.225），OVERLAY 纪律，md5 86a2189c… 对拍 OK，.env intact（备份 /tmp/env-backup-pa5-20260915T225307）
代码批次：单 commit（45 files +2708）含 A1–A7 全量；**全量回归 2063/2063 绿**（26 跳过=无 docker IT）
结果：**部署成功 + HMAC 五探针全中 + zipkin receiver 内网 202 + 验链全绿 runs=29**

## 一、PA-A5 决策溯源（L8-5）

- `DecisionProvenance`（域内 record，8 字段：agent_build_sha/policy_version/prompt_version/model_provider/model_id/tool_name/tool_version/tool_schema_hash）：canonical JSON 固定键序、null 省略、控制字符转义、无 Jackson 依赖。
- 生产接线：`AlertAm4Config.am4ShadowToolFace`（R0/R1 只读面唯一生产 ToolGateway 构造点）→ `ReadOnlyToolFace` 新全参形态 → `ToolGateway` 7 参形态 → `recordIntent`：R2/R3 意图事件 `TOOL_INTENT_VALIDATED` 载荷携带统一 provenance 块（build sha + policy version + 工具 schema 锚）。
- 配置：`app.alert.provenance.build-sha`（docker profile → `APP_PROVENANCE_BUILD_SHA`，缺省 unknown 显式可见）/ `policy-version`（缺省 pa-prod-v1）；compose 已透传。
- 测试：DecisionProvenanceTest 5 + ToolGatewayProvenanceTest 2（装配→意图事件含块；未装配→不含块，旧装配零漂移）。
- **195 行为面说明**：生产注册面当前全 R0（R2 物理不存在），意图事件生产零发生——provenance 真值落账随 Phase D R2 解封首触发即可观测；本波由 7 用例钉死 JSON 形状与接线完整性。容器 env `APP_PROVENANCE_BUILD_SHA=<set>` 实证（/proc/1/environ 键面）。

## 二、PA-A6 OTel 接线（L8-6）

- 依赖：`io.zipkin.reporter2:zipkin-reporter-brave`（Boot BOM 经 zipkin-reporter-bom 管 3.4.3，零显式版本钉；aliyun 镜像 200 实证）。**注**：首版误用 `io.micrometer:micrometer-tracing-reporter-brave`——该坐标中央仓不存在（404 实证），自检拦截，未出包。
- 配置三件：`application-docker.yml` `management.zipkin.tracing.endpoint=${ZIPKIN_ENDPOINT:http://otelcol-control:9411/api/v2/spans}`；compose `ZIPKIN_ENDPOINT` 透传（置空可显式关导出）；`otelcol-config.yml` 增 zipkin receiver（0.0.0.0:9411）+ traces 管道 `[otlp, zipkin]` → tail_sampling（错误/慢全保，健康 10%）→ 上游。
- 采样双道面不变：应用侧 `APP_TRACE_SAMPLING`（0.1）第一道，collector tail_sampling 第二道。INV-AM5-8：collector 不可达只丢遥测面（异步 reporter 丢弃），业务零影响。
- 195 实证：
  - otelcol 重载后 health 端点 `{"status":"Server available"}`（upSince 14:55:06Z）；
  - **zipkin receiver 内网 202**：`docker exec deploy-control-app-1 curl -X POST http://otelcol-control:9411/api/v2/spans -d '[]'` → 202（应用同网络别名可达性同径实证）；
  - 应用容器 `ZIPKIN_ENDPOINT=<set>`；启动 10 分钟窗 `Failed to export|zipkin error` 计数 = 0；
  - bind-mount 配置变更不触发 compose 重建——显式 `docker compose restart otelcol-control` 重载（部署脚本纪律登记）。

## 三、PA-A7 HMAC-SHA256 双因子（L0-1）

- `AlertWebhookHmacFilter`（OncePerRequestFilter）：签名覆盖 `keyId\nMETHOD\nURI\ntimestamp\nnonce\nsha256hex(body)`（method/path 入签防搬运重放）；±300s 时间窗；nonce 防重放（进程内，容量 1e4，TTL 2×窗）；常量时间比对；失败 401 JSON 不回落 bearer（故障期安全等级不降）；无签名头或未配置密钥=直通原 bearer 链（AM 无法计算 HMAC，机器兼容硬约束）。
- **PA-BUG 自检拦截（部署前）**：首版 verify 直接读 `request.getInputStream()` 后以原始 request 放行——下游 `@RequestBody` 将拿到空体。修正为缓存 body wrapper（`CachedBodyRequest`）后再放行，并用例钉死（utH01 下游 body 完整可读断言）。
- 配置：`app.alert.webhook.hmac-keys="keyId:secret,..."`（compose `APP_ALERT_WEBHOOK_HMAC_KEYS`，缺省空=bearer 单因子不变）；密钥不落库不落日志，轮换=.env 改值+`up -d`。
- 测试：AlertWebhookHmacFilterTest 8 用例全绿。
- **195 五探针实录**（探针密钥 id=pa-probe 部署期幂等注入 .env，值不回显）：

| # | 探针 | 期望 | 实测 |
|---|---|---|---|
| 1 | 有效签名（body `{"alerts":[]}`） | 非 hmac-401（到 controller 面） | **400 `{"error":"缺少/非法字段: version"}`** = 验签放行，controller schema 面拒绝 ✅ |
| 2 | 同签名篡改 body | 401 hmac | **401 `{"reason":"hmac verification failed"}`** ✅ |
| 3 | nonce 重放（复用 #1 nonce） | 401 hmac | **401 hmac** ✅ |
| 4 | 未知 keyId | 401 hmac | **401 hmac** ✅ |
| 5 | 无签名头 + bearer（AM 原链回归） | 与 #1 同径 | **400 同 #1**（bearer 链与 HMAC 链在 controller 面完全等价）✅ |

  探针 6（复用 #1 签名换 nonce）401——签名与 nonce 绑定的附加负例，行为正确。
- 双凭证兼容裁定落定：AM 现网投递（bearer）零改动；新机器凭证面可平滑采纳 HMAC 双因子。

## 四、全容器收官面（10 分钟窗回扫）

- 容器 6/6 Up（control-app 重启 14:54:05 启动完成 13.996s；otelcol 重载 14:55:06）。
- `APPLICATION FAILED`=0、` ERROR `=0；WARN 仅两条已知 LEGACY_UNKNOWN 孤儿 Run 对账告警（演示期遗留，预期语义"不猜结果不自动恢复"）。
- 验链作业同容器共存：`EventChainVerifyLoop 启动 interval=PT24H` + 启动首轮 **验链全绿 runs=29**（A2 回归零漂移）。
- health 200；alert_inbox 终值 PROCESSED=589 / DEAD_LETTER=8（历史）/ **QUARANTINED=1**（A3 注入隔离件保持人工放行态）。

## 五、Phase A 收官与遗留

Phase A 七片（A1 进度面 / A2 事件哈希链 / A3 注入隔离 / A4 五模式循环卫兵 / A5 决策溯源 / A6 OTel / A7 HMAC）全部落码 + 回归 + 195 实证完毕。遗留（不阻断，随解锁窗口归位）：
1. provenance 真值落账观测随 Phase D R2 解封首触发（生产全 R0，意图事件零发生）。
2. HMAC nonce 防重放为单实例进程内缓存——多实例部署时引入共享存储关闭（已登记）。
3. RcaWorker/RunReconciler 等 Tracer 深度挂点（tick span 先行于 AlertInboxProcessor）随 L8 观测面深化排期。
4. 遥测上游出账面（tail_sampling 后→上游后端）不在本批证据面，collector 入口 202 + 零导出错误为本批实证边界。
