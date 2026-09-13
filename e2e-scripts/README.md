# e2e-scripts —— B2 正式验证工具目录（S0/U01~U03 修复版）

2026-09-13 起为正式工具位。v1 时代的脚本以其时形态冻结在
`docs/测试证据/R7/runs/b1-input-capture-20260913/` 与 `b2-cl06-20260913/`（归档原件不改）。

## 当前套件：CL-06 跨轮记忆 v2

| 文件 | 角色 | 关键契约 |
|---|---|---|
| `b2-cl06-verify2.py` | 跨轮 prompt 可达性验证器 | 退出码 PASS=0/FAIL=1/ERROR=2/INCONCLUSIVE=3；`verify2-result.json`（suiteVersion/runId/status/assertions/coverage/configDigest）；括号配平信封解析（支持嵌套）；非空 witness 缺失=INCONCLUSIVE 不算语义过（N01）；父序冻结契约显式 FAIL（N04）；DB 调用失败/不合形行=ERROR 不静默丢（N05） |
| `b2-verify2-selftest.py` | 离线变异自测 | 八案全谱：v1 四假绿案（digest/反证丢/父序反转/空父）必须被拒+N05/N08+单轮；全过=SELFTEST-PASS |
| `e2e-b2-cl06-v2.sh` | 真窗驱动 | phase7 绑案仲裁纯 DB（winner=本案 pub SENT+outbox；loser=本案零 pub 零 outbox+同 incident 更早 SENT winner——N09/N11）；phase9 LAG 同作用域父链（允许 revision 间隙 N13）+LEFT JOIN NULL-safe 锚点（N14）；phase10 verify2 退出码门（SUITE PASS 依赖验证器成功）；EXIT trap 恒输出 `RUN_ID_RESULT=`（N07） |
| `b2-cl06-retry2.sh` | 试验包裹器（v2.1） | 固定 N 有效试验全记录（质量口径，不以单次通过冒充稳定）；INVALID（零委派采样面）如实记录不计入；**分类优先级=V2 已知时先于 DRV**（driver phase10 门设计上以 exit1 编码 verify2=3）；run 目录取 `dirname(grep -l 命中行)`；任一有效 FAIL/ERROR→exit 1、任一 verify2=0→exit 0、全 INCONCLUSIVE→exit 3 |
| `b2-cl06-override-v5.yml` | N18 场景（v5.2 终版） | 两批委派序+两批间强制独立直查步（steps≥2→记忆行≥2，v5/v5.1 迭代见 b2-cl06v2-20260913 执行记录）：①指标直查→②delegate g1→③回执后独立直查步 metric_value 复查→④delegate g2（第二批）→⑤final。**witness 注记**：现行回执生产者成功路径 counter_refs/missing 恒空（BA-142）→该场景终态=INCONCLUSIVE（结构过/槽恒空），非空 witness 需回执生产面增强后重开 |
| `b2-cl06-v2-relaunch.sh` / `b2-cl06-v2-snap.sh <label>` / `b2-cl06-v2-restore.sh` | 发射/快照/复原 | 窗口静默 pgrep（发射命令行不得含被匹配模式串字面量）；快照必须按批标签分目录（防同名覆盖）；复原=撤 override 重建+flagd off+env 面验证 |

依赖：`e2e-r7-common.sh`（部署侧 /opt/build/，随驱动同目录）。归档证据中的
v1 版本及其四假绿面详见 `docs/告警-BUGLOG与B2测试审查-统一收口技术方案-v1.md` §4
与 b2-cl06-20260913 执行记录 §八勘误注。v2 真窗收官证据（18 run/五批全记录/
BA-141/BA-142 立案面）：`docs/测试证据/R7/runs/b2-cl06v2-20260913/`。
