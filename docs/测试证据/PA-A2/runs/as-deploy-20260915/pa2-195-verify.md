# PA-A2 195 部署与真机验证台账（2026-09-15）

批次：Phase A 第二片（V112 事件哈希链 + EventChainVerifyLoop 每日验链）+ E0 回归不变量 v1（本机 CI 门）
部署机：195（146.56.195.225），OVERLAY 纪律，md5 031f73bc… 对拍 OK，.env intact
结果：**部署成功；哈希链写/验/每日作业三类证据全过；legacy 段边界语义实证**

## 一、部署与装配面

- flyway `112|true`；rca_event 新列 `prev_hash`/`event_hash` varchar(64) 在库。
- 启动日志：`EventChainVerifyLoop 启动 interval=PT24H` + 首轮 **`验链全绿: runs=28`**——28 个存量 run 全部过验链（legacy 事件 NULL 双列按段边界跳过，未误报）。
- health 200，APPLICATION FAILED=0。

## 二、新写入事件的链证据（run 4950d39b，2026-09-15 10:36）

| 事实 | 值 | 证明 |
|---|---|---|
| 首个 append 事件 | seq=2，prev_hash=`GENESIS`，event_hash=`45021458…` | 链写随 append 同事务落库；seq=2 起算为**既有约定**（历史 7 个采样 run 首事件 seq 全部=2，seq=1 为 run 创建保留位） |
| 结构自检 | `breaks=0`（prev 非空 + 有前驱行时 prev=前行 hash） | 链式相联 |
| 验链器 | 启动首轮全绿 28 runs | 重算比对路径真机可用 |

## 三、同场复证（探针同 run）

- PA-A1 进度列：attempt `SUCCEEDED, act=10:37:18 > prog=10:36:58（started）`——心跳推进与语义分离持续成立。
- BA-146 修复保持：critical 复燃 PROCESSED（无死信）。

## 四、E0 回归不变量 v1（本机 CI 门，不随部署）

`RegressionInvariantsV1Test` 7 用例全绿：A1 旧 worker 栅栏 / A3 终态零出边 / A4 活跃 run 铸造闸 / A7 零预算零触网 / A8 未授权工具零执行 / A10 R2 零副作用 / A11 租约活不可重排；A2/A5/A6/A9/A12 以 javadoc 映射到既有专属测试族（R7ActionGuardAdmissionTest、ExA4bIncidentProjectorTest、RcaWorkerTest、RunReconcilerTest、投影 join-tx IT）。

## 五、NOT_RUN / 遗留

- Testcontainers 真 PG IT（本机无 docker）——以 195 真机台账替代。
- Merkle checkpoint + 外部 WORM 锚（R9 增强项）未做，另立增量。
- 合成探针 incident（CheckoutRpc… PA-A2 probe）已注入 resolved 收口；探针死信行保留作 BA-146 证据。
