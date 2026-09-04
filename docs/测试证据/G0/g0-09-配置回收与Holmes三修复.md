# G0-09 证据:AM0 配置回收 + Holmes 运行时三修复(BA-12②③④)

执行日期:2026-09-04。方案依据:`docs/告警G0-收口技术方案.md` G0-09。

## 一、AM0 配置回收(→ `deploy/alert/`)

从 195 `root@146.56.195.225:/opt/projects/alert_agent/` scp 回收,逐一 SHA-256 对拍
(195 `sha256sum` vs 本地 `Get-FileHash`),**全部逐字节一致**:

| 仓库文件 | SHA-256(前 16) |
|---|---|
| `deploy/alert/prometheus/prometheus.yml` | `61e3dbec7d71791e` |
| `deploy/alert/prometheus/rules/prometheus-rules-checkout.yml`(sloth v0.16.0 生成) | `f8d987549a26cb4a` |
| `deploy/alert/prometheus/rules/empty-groups.yml` | `761adf8d97e15214` |
| `deploy/alert/alertmanager/alertmanager.yml` | `06625eba5b5b8468` |
| `deploy/alert/holmesgpt/Dockerfile` | `a0d82955537f516d` |
| `deploy/alert/otelcol/otelcol-config-extras.yml` | `4ebc7cbbe868cf6e` |
| `deploy/alert/otelcol/compose.am0-override.yaml` | `fbab6770bf547455` |

另新建 `deploy/alert/docker-compose.yml` 骨架(prometheus/alertmanager/holmes 三服务,
参数与 195 实机 `docker inspect` 核对:镜像 tag、mem_limit 300m/1536m、
cap_drop ALL + no-new-privileges、127.0.0.1 端口绑定、网络拓扑
prometheus/alertmanager→`opentelemetry-demo`(外部)、holmes→`alert-net`)。

安全核验:7 个文件目检零密钥;195 的 `.env.holmes`(含真实密钥)**不回收**
(密钥纪律);alertmanager webhook 现指 echo-receiver(AM0 钻探态),G0-10 切 control-app。

## 二、BA-12② 响应限读(HolmesClient)

- `HolmesClient` 构造器第 5 参 `maxResponseBytes`(正数校验);`chat()` 由
  `.retrieve().body(String.class)` 改为 `.exchange()` + `readBounded()`:读至
  `maxResponseBytes+1` 字节即止(超限探测),截断体交结构验证链判 REJECTED_*。
- `AlertFlowConfig.holmesClient` 注入 `${app.alert.holmes.max-response-bytes:1048576}`
  (与 EvidencePackageValidator 同键同值——两道闸同预算)。
- 测试(WireMock):
  - `ba12_boundedReadStopsAtCap`:8KB 响应 + 64B cap → 读到的 body ≤65 字符;
  - `ba12_truncatedBodyRejectedByValidationChain`:完整合法包 + 200B cap →
    截断体 JSON 解析失败 → `REJECTED_MALFORMED`,账本仍 SUCCEEDED(截断是
    client 防御不是调用失败),responseDigest 落账。

## 三、BA-12③ 账本 UNKNOWN 语义(HolmesInvestigationExecutor)

- 超时/网络类(`HolmesTransportException`)账本终态 FAILED → **UNKNOWN**
  (结局不确定:请求可能已被 Holmes 收下并计费;诚实对账);重试决策不变
  (FAILED_RETRYABLE + TIMEOUT,模糊窗口有界重复由 max_attempts 封顶)。
- HTTP 类异常(4xx/5xx)仍显式传 FAILED(结局确定)。
- 测试:`exA04_timeoutIsRetryableWithLedgerFailure` 断言账本
  `UNKNOWN + http_status null + errorClass TIMEOUT`;`exA11`(HTTP 500)保持 FAILED。

## 四、BA-12④ Hikari 显式配置

`application-docker.yml` datasource 段:
`hikari.maximum-pool-size: 12` + `connection-timeout: 5000`(§15 连接预算;
池耗尽 5s 快速失败,对齐 AM 10s timeout,不再排队挂死)。

**落位说明**:方案原文写 application.yml——实际默认 profile 显式排除
DataSourceAutoConfiguration,主 yml 的 hikari 键是死配置;落 application-docker.yml
(生效点)语义等价。

测试:`AlertConfigBridgeTest.dockerProfilePinsHikariPoolBudget`(YamlPropertySourceLoader
加载 application-docker.yml,断言两键值)。

## 五、测试结果(本地,2026-09-04)

- `HolmesInvestigationExecutorWireMockTest`:**15/15 绿**(13 既有 + 2 新增限读)
- `AlertConfigBridgeTest`:**4/4 绿**(3 既有 + 1 新增 Hikari)
- 日志:`var/g0-09-holmes-test.log`、`var/g0-09-bridge-test.log`

截图留证归 G0 收尾统一补(终端截屏,见 `docs/告警-测试记录.md` G0 段)。
