# M6-07 195 真机验证证据包（Holmes 生产路径下线回归，2026-09-09）

- **验证对象**：M6-07 Holmes 生产路径下线——compose 面 holmesgpt 服务/卷/network 摘除（litellm 保留，C-63）、密钥回收四零、路由无 Holmes（C-77 守卫 + BA-60/V35 审计归属语义）、历史报告/审计可读、drain barrier 回放、制品恢复路径实跑、percent=100 全量 NATIVE 收官。
- **代码链**：`ad0a876`（AlertFlowConfig 摘 holmes bean 与铸造分支）→ `fc88c6c`（compose 面+密钥回收+.env.example/README）→ `f620259`（BA-58 IT 夹具修复）→ `39e4478`（BA-59 启动自检凭证门三面摘除）→ `4d3ff32`（BA-60 V35 摘 NOT NULL + CanaryRouter 审计归属语义）。
- **官方全量回归（零跳过）**：`m607-verify5.log`——195 `/opt/build/pr/control-app`（与 HEAD=4d3ff32 五探针 sha256 双侧全等）`mvn clean verify`：**单测 962 + IT 134 = 1096 全绿，Failures 0 / Errors 0 / Skipped 0，BUILD SUCCESS**（相对 BA-60 前 961+133：+1 单测=V35 契约钉、+1 IT=holmesWillingDecisionRowCommitsWithNullRunId）。verify3（963+133，BA-58 后）/verify4（961+133，BA-59 后）为过程官方绿留档。
- **BA-59 真启动崩溃循环**（部署段首轮，`m607-deploy-ba59-red.log`）：B25 运行时间门强查 HOLMES_API_KEY → APPLICATION FAILED 24×health 000；修复后 `m607-deploy2-ba59-green.log`（镜像 `afc57280…`，health 200）。
- **BA-60 红绿**：红=`m607-ba60-red.log`（定向 IT 真 PG：`canary_route_decision_run_id_fkey` 提交点 23503，6 案 5 绿 1 红）；修复=V35+路由归属；绿=`m607-ba60-green.log`（6/6）。V35 应用：先 pg_dump 备份 149129555B sha256 `3ff560c8…` → flyway 35|t → run_id nullable。
- **BA-60 真栈复发（E2E 主动暴露部署滞后）**：E2E 首轮（run 010204Z）时 195 在跑镜像仍是 BA-59 时代（`afc57280…`，不含路由修复）——旧路由器给决策行写幽灵 runId → V31 deferred FK 提交点 23503 → 投影事务整组回滚 → inbox 10s 周期重投影死循环直至 DEAD_LETTER×3。PG 侧 5 连 FK 拒杀固化于 `ba60-live-manifestation.log`（与 control-app 守卫行「决策照记不铸 run」秒级对齐）。重建部署 `m607-deploy3-ba60-image.log`（镜像 `430a3fd3…` = 4d3ff32，health 200，零 holmes 令牌）。
- **E2E-AM6-07**（`runs/` 三轮全留痕，诚实记录）：
  - run `20260909T010204Z`：phase1 FAIL——上条所述部署滞后（非脚本/产品缺陷，镜像重建后消除）；
  - run `20260909T010941Z`：phase0~3 PASS，phase4 FAIL——**套件脚本自身检查缺陷**（`grep -c '='` 把证据文件中文标题行的 `==` 计入键名残留），脚本修正后消除；
  - run `20260909T011231Z`（官方绿，`e2e-am6-07-final-green.log`）：八相位全 PASS——①退场姿态（health 200/nativeReady/holmes 容器·卷全零/alert-net 无 holmes/litellm 在场）；②percent=0 → BUCKETED_HOLMES 决策行 `run_id IS NULL` + 同键零 run + 守卫日志 1 次；③WHITELISTED → NATIVE 全链（run_id 非空原子对 → SUCCEEDED engine=NATIVE config_digest → 报告/发布/外发/NATIVE_INVESTIGATE DONE/DAG≥3 终态）；④审计两态共存（V35 前 `legacy_with_run=46` × V35 后 `retired_without_run=2`，NATIVE 决策↔run join 可读 `native_pair=8`）；⑤密钥归并四零（.env/容器 env/双栈渲染，键值零回显）；⑥drain B1~B4=0|0|0|0 + HOLMES 计数 137 零增；⑦制品恢复实跑（封存清单锚 + V35 备份 sha256 恒等 + 最旧 HOLMES 报告读回 RTO=1s）；⑧收官 percent=100 全量 NATIVE（BUCKETED_NATIVE → SUCCEEDED）。
- **发布指针史（全链留档）**：`43f23ecc…`（M6-05 percent=0 锚）→ `141ee3b4…`（run1 percent=0）→ `6d44f490…`（run2 p100 白名单）→ `6763ff46…`（run3 percent=0）→ `743c09fb…`（run3 p100 白名单）→ **`99f1e6cf…`（收官终态：percent=100 全量 NATIVE，M6 里程碑弧终点；runs/20260909T011231Z-am6-07/final-posture.txt）**。收官姿态为**待用户复核的里程碑终态**（1%→10%→50%→100% 弧终点）；回滚靶 D_PREV 留档但本套件不执行回滚——**不宣称一键回切（C-69）**：回滚是发布指针操作面（`/api/config-bundles/rollback`），非 Holmes 服务复活开关。
- **决策裁定**：`../../../告警AM6-Holmes退场决策记录.md`（M6-06 产出）为唯一裁定源；本包补齐其 §9 步骤⑦后的下线实施证据。
- **密钥面**：全件零密钥（bearer/PG 口令经 195 侧 600 权限 env 注入 + 脱敏管道；`runs/*/secret-scan.txt` 只列键名/词形零值回显；入 git 前密钥字样扫描零命中）。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| e2e-am6-07-final-green.log | E2E-AM6-07 官方绿全日志（八相位，suite=20260909T011231Z） |
| runs/20260909T010204Z-am6-07/ | E2E 首轮（BA-60 旧镜像复发面：phase1 轮询超时+posture/锚证据） |
| runs/20260909T010941Z-am6-07/ | E2E 第二轮（phase0~3 PASS + 脚本检查缺陷面） |
| runs/20260909T011231Z-am6-07/ | E2E 官方轮全套证据（posture/secret-scan/audit-two-states/drain-replay/recovery-manifest/oldest-report/final-posture/scenario-results.json 等） |
| ba60-live-manifestation.log | BA-60 真栈复发 PG 侧 5 连 FK 23503（与守卫行秒级对齐） |
| m607-ba60-red.log / m607-ba60-green.log | BA-60 定向 IT 真 PG 红（23503）/绿（6/6） |
| m607-deploy-ba59-red.log / m607-deploy2-ba59-green.log | BA-59 部署段崩溃循环 / 修复后部署绿 |
| m607-deploy3-ba60-image.log | BA-60 修复镜像（430a3fd3…）重建部署绿 |
| m607-verify3.log / m607-verify4.log / m607-verify5.log | 全量回归官方绿三部曲（963+133 / 961+133 / **962+134 零跳过收官**） |
