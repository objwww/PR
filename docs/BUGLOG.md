# BUGLOG —— 缺陷与事故记录

> 规则：遇到 Bug 当场记录，模板字段缺一不可（见 skill `milestone-workflow` 第五节）。
> 状态：已修复 / 根因待查 / 观察中。修复必须登记回归测试编号。

## 模板

| 编号 | 状态 | 现象 | 前因后果 | 根因 | 解决方案 | 预防措施 | 关联 |
|---|---|---|---|---|---|---|---|

## 记录

### INC-01 —— 已修复

- **现象**：初版 Outbox 修订把 `DRIFTED` 作为第九态塞进命令状态机。
- **前因后果**：v2 §5.2 冻结八态但 CONFIRMED 行提及"标 DRIFTED"，状态机定义不完整；v2.1 修订一将其扶正为第九态。评审发现语义冲突：命令历史（曾确认成功，不可变事实）被资源现状（远端对象后来消失）污染，且级联/依赖判定语义随之含糊。
- **根因**：把"发布尝试的生命周期"（命令视角、历史）和"远端对象的存续"（资源视角、现在时）混在同一状态机里——两个变化轴塞进一张表。
- **解决方案**：v2.2 §1——Outbox 恢复八态；漂移建模为独立的 `publication_resource` 表（Publisher 写入，Control 只读），修复动作以新命令表达。
- **预防措施**：状态机设计评审时先问"这个状态描述的是谁的生命周期"；实体职责单一化。
- **关联**：冻结文档 B13、ADR-018。

### INC-02 —— 已修复

- **现象**：Patch 状态机缺 `VERIFICATION_FAILED`，`VERIFYING` 无失败出口。
- **前因后果**：数据设计评审中发现；沙箱验证失败是正常业务结果，若无此状态则验证失败无路可落，门禁逻辑被迫绕路。
- **根因**：状态机只画了成功路径，未对"安全门的否决结果"建模。
- **解决方案**：v2.2 §2——增加 `VERIFICATION_FAILED` 终态 + `failure_class`（TESTS_FAILED/INFRA/TIMEOUT）；INFRA 类允许受限回转重试，TESTS_FAILED 终态。
- **预防措施**：任何状态机评审必须穷举每个非终态的失败出口。
- **关联**：冻结文档 F3、AFT-08（M5 承重）。

### INC-03 —— 已修复

- **现象**：Revision/Generation 拆分后，fence 只比 `pr_revision_id`，旧 policy 世代在途命令可穿过 fence。
- **前因后果**：数据设计把 `policy_version` 从 `revision_id` 中拆出（合理），但 fence 规则未同步更新——同代码、旧规则的 PENDING 命令会被放行，旧结论写到新规则世代。
- **根因**：拆分一个复合概念时，只迁移了数据结构，没迁移依附在原概念上的判定规则。
- **解决方案**：v2.2 §3——fence 改为 `revision_id + publication_epoch`，epoch 与 revision/policy 变更同事务递增；保留 fence_mode 分类（OWNED_GENERATION 放行旧世代收尾）。
- **预防措施**：拆分复合概念时，列出所有引用旧概念的判定逻辑逐一迁移。
- **关联**：F9、ADR-010；Kafka KIP-98/320 先例。

### INC-04 —— 已修复

- **现象**：无条件 digest CHECK 约束使"验证对象 ≠ 提案对象"无法落库。
- **前因后果**：`ck_patch_digest_consistency` 要求 verified == patch digest；一旦发生换包异常，UPDATE 直接抛错，取证事实丢失。
- **根因**：把"门禁判定"误当"格式约束"写进 DB——约束挡住了最需要被记录的异常事实。
- **解决方案**：v2.2 §4——改为条件 CHECK（仅 VERIFIED 状态强制相等）；失配允许落库但状态只能进 VERIFICATION_FAILED；完整判定仍在应用层 gate。
- **预防措施**：DB 约束管不变量，业务判定留应用层（B25 分层原则）。
- **关联**：AFT-08；回归测试 CT-05。

### INC-05 —— 已修复

- **现象**：195 服务器备份时 tar 读到正在写入的 mongo 卷，报 "file changed as we read it"，备份脚本中段终止。
- **前因后果**：首版备份脚本 `set -e` 遇 tar 返回码 1 即退出，后续代码目录、nginx 配置打包未执行——若未细看日志，会把半成品备份误判为完成。
- **根因**：对运行中容器的数据卷做文件级 tar 必然遇到读写竞争；脚本缺少对 tar 部分成功语义的容忍和分步校验。
- **解决方案**：`--warning=no-file-changed` + 显式容忍；逻辑导出（mysqldump/mongodump/pg_dumpall）与物理卷快照双保险；每文件 `gzip -t` 校验 + 清单核对。
- **预防措施**：备份 SOP：导出后必须逐文件校验并抽查内容；后台任务日志必须读完再报完成。
- **关联**：服务器清理操作手册。

### INC-06 —— 已修复

- **现象**：`pg_dumpall ... | gzip > file` 产出 20 字节空备份文件。
- **前因后果**：pg_dumpall 因 role "postgres" 不存在而失败，错误输出被 `2>/dev/null` 吞掉，gzip 正常退出使 `||` 兜底失效——**备份看起来存在实则为空**，是最危险的静默失败。
- **根因**：① 管道掩盖上游退出码（无 pipefail）；② 盲目抑制 stderr；③ 假设容器默认用户为 postgres 而未核实（实际为 agent）。
- **解决方案**：`set -o pipefail`；从容器 `printenv POSTGRES_USER` 取真实用户重导；导出后 `zcat | head` + `CREATE DATABASE` 计数抽查内容。
- **预防措施**：备份不仅校验"文件存在且 gzip 完整"，必须校验"内容含有预期数据"；禁止无差别 `2>/dev/null`。
- **关联**：备份 SOP；与 INC-05 合并为"备份静默失败"类教训。

### INC-07 —— 已修复

- **现象**：M0 技术方案 v1.0 被用户评审抓出 6 处必修缺陷 + 4 处顺手修正。
- **前因后果**：评审发生于 G1 方案门（编码前）。六项必修：① Publication Reconciler 名义上推迟到 M1，但 M0 的崩溃恢复测试（ST-03/04）实际在测它——组件无归属；② `work_item` 表与租约语义已定义但没有消费者（WorkItem Worker），持久化执行空转；③ T1 先插 PRRevision 后算 digest，与"不可变行 insert 即完整"自相矛盾；④ Handler 一边被宣称不依赖 WriteAdapter、一边拿 token 直调 GitHub，B27 结构防线漏了；⑤ 保序游标 `last_applied_sequence` 跨不过 SUPERSEDED/FAILED_TERMINAL，会造成假跳号；⑥ "Control↔Publisher 无任何直接调用"与"申请只读 token 窄接口"两处表述矛盾。四项顺手：422 应分类（head 变化→SUPERSEDED）；启动自检应用 has_table_privilege；Control 解包 tarball 也要防穿越/炸弹；INC-05/06 应移出架构文档。
- **根因**：方案写作时按"组件清单"思考而非"执行循环"思考——表和状态机定义了，但追问"谁在跑这个循环"才发现没有执行者；事务内容编排时把 digest 计算放在了错误的一侧。
- **解决方案**：M0 方案 v1.1 全部修订落地（T10 WorkItemWorker、T13 OutboxRecoveryScanner 新增；T0/T1 顺序修正；Handler 只产类型化请求对象；`last_resolved_sequence` 游标语义；表述统一）。
- **预防措施**：工序 1 方案评审增加检查项——"每张队列/状态表是否有明确的消费者组件""每笔事务的网络 I/O 是否在事务外"。
- **关联**：M0 方案 v1.1 §2/§3/§6；冻结文档 B27、v2.1 修订三。

### INC-08 —— 已修复

- **现象**：`deploy/db/01-roles.sh` 查询 `pg_roles.rolename`，该列不存在，postgres 容器 initdb 直接报错，T02 验证阻断。
- **前因后果**：脚本由主会话在编码前手写，未经任何执行验证；T02 在 195 服务器首次实际运行时暴露。影响仅限该脚本两行，schema/授权 SQL（V1/V2）本身一次通过。
- **根因**：凭记忆写字段名未核实（正确列名 `rolname`）；写 SQL 后不执行就交付的坏习惯。
- **解决方案**：两行标识符 `rolename` → `rolname`（T01/T02 执行 agent 现场修复并复验通过）。
- **预防措施**：任何 SQL/脚本落盘即视为"未验证"；验收必须含真实执行输出（本次 12 条权限断言全部留档）。回归测试：CT-04（L2 权限矩阵）将长期覆盖。
- **关联**：T02 验收；CT-04。

### INC-09 —— 观察中（环境限制，非代码缺陷）
- **现象**：195 服务器（CentOS 7，内核 3.10）默认 seccomp profile 下 postgres:16-alpine initdb 报 `pg_wal` 写入 EPERM，需 `--security-opt seccomp=unconfined` 才能启动。
- **前因后果**：T02 验证时发现。内核 3.10 的 seccomp 不支持新镜像使用的系统调用。对 M0-T18 有影响：compose 的 B16 hardening 清单需要与 seccomp 自定义 profile 兼容，不能简单 `seccomp=unconfined` 了事。
- **根因**：宿主机内核过旧（CentOS 7 已 EOL）与新容器镜像的 syscall 面不匹配。
- **解决方案**：M0 阶段测试容器临时 `seccomp=unconfined`；T18 时评估自定义 seccomp profile（从默认 profile 放行缺失 syscall）或升级宿主机内核/换 117 部署。
- **预防措施**：T18 hardening 验收（DP-04）增加"seccomp 策略显式声明"检查项；部署文档记录该限制。
- **关联**：T18、DP-04。

### INC-10 —— 已修复

- **现象**：`SafeTarExtractor` 用 commons-compress 的 `TarArchiveEntry.isFile()` 过滤非普通文件，但 1.27.1 版本中设备条目（字符/块设备）的 `isFile()` 也返回 true，恶意设备文件险些漏检。
- **前因后果**：T06 实现安全解包器时按库谓词的字面语义编排防线顺序（先 `!isFile()` 判特殊文件）；UT-09 的设备文件样本测试暴露漏检。若漏到生产，解包不可信 PR 快照时可能尝试写设备节点（解包侧有 rootless 限制，实际危害有限，但防线语义被破坏）。
- **根因**：对第三方库谓词语义的想当然——`isFile()` 在该库中是"非目录"语义而非"普通文件"语义；防御性代码必须实证依赖库的真实行为。
- **解决方案**：改为显式前置拦截 `isCharacterDevice()/isBlockDevice()/isFIFO()`，再做普通文件判定；回归测试 = UT-09 设备文件用例。
- **预防措施**：安全防线的谓词必须用独立样本实证第三方库行为后再定顺序；UT-09 恶意样本集长期保留。
- **关联**：T06、UT-09、B-6/B22。

### INC-11 —— 已修复（设计偏差，T17 集成暴露）

- **现象**：V2 grants 只给 publisher_app 对 pr_subject 的 SELECT，T3-B 推进 `last_resolved_sequence` 游标在真库上 38 次 permission denied，13 个 IT 用例物理不可行。
- **前因后果**：V1/V2 由主会话按冻结文档第七章角色矩阵手写（矩阵原文 publisher 对 pr_subject 仅 SELECT），但评审修正 #5 的游标语义要求 publisher 在标终态的同事务推进游标——矩阵与游标语义脱节，纯静态评审没发现，T17 真授权环境下立刻暴露。
- **根因**：权限矩阵设计时只考虑了"publisher 不能换届/不能发号"，漏算了"游标推进权归 publisher"这一例外列。
- **解决方案**：列级最小授权 `grant update (last_resolved_sequence, updated_at) on pr_subject to publisher_app`；实测 PG16 语义：列级 UPDATE 权满足 FOR UPDATE 行锁，表级 has_table_privilege 仍为 false，epoch/序号/revision 指针 publisher 依旧改不动（CT-04 负探针把守）。
- **预防措施**：权限矩阵评审时逐列追问"谁在哪个事务里写这列"；矩阵变更必须配真库断言（CT-04 常驻）。
- **关联**：评审修正 #5、CT-04/CT-07、V2__grants.sql。

### INC-12 —— 已修复（T17 集成暴露）

- **现象**：`PostgresWorkItemRepository` 的 CLAIM_SQL 中 `:now + make_interval(...)` 参数被 PG 推断为 interval，claim 直接类型错误。
- **根因**：JDBC 参数类型推断与 PG 算符重载交互的边界坑，纯单测（无真库）覆盖不到。
- **解决方案**：`CAST(:now AS timestamptz) + make_interval(...)` 显式定类型。
- **预防措施**：含类型推断敏感算符的 SQL 必须有真库 IT 覆盖（CT/ST 套件常驻）。
- **关联**：CT-02、ST-08。

### INC-13 —— 已修复（T17 集成暴露）

- **现象**：`PostgresStepAttemptRepository` UPSERT 把自由文本异常 message（含中文）直接绑进 jsonb 列 error_detail，真库报错。
- **根因**：jsonb 列需要 JSON 值而非裸文本；且 `to_jsonb(NULL)` 存在多态推断坑。
- **解决方案**：`to_jsonb(CAST(:errorDetail AS text))`。
- **预防措施**：jsonb 列写入统一走显式序列化助手，禁止裸绑字符串。
- **关联**：EX-06。

### INC-14 —— 已修复（T17 集成暴露）

- **现象**：ReviewOrchestrator 账本缺口——STEP_RESULT 失败不带 error_class/code、模型超预算无 BUDGET_EXCEEDED 事件、安全拒绝无 SAFETY_REJECTED 事件、Run 状态迁移不落 RUN_STATE_CHANGED，导致 fold(events) 与投影对不上。
- **根因**：T2 实现聚焦状态推进，事件落账按"主路径"写全、失败路径漏记；fold 一致性测试（ST-01）一跑即穿。
- **解决方案**：补齐四类事件落账点；ST-01/EX-06/EX-10 断言 fold 一致。
- **预防措施**："每个状态迁移点是否有对应事件"列入编码自查清单；fold 一致性断言作为所有闭环用例的固定尾检查。
- **关联**：E10、I9、ST-01。

### INC-15 —— 已修复（T17 集成暴露）

- **现象**：REVISION_INVALIDATED 事件挂在**新** Run 的事件流且先于 RUN_CREATED，Projector fold 新 Run 时遇到未创建先作废，直接炸。
- **根因**：事件的归属流选错——作废是被作废旧 Run 的事实，应落旧 Run 的流。
- **解决方案**：事件移到旧 Run 流（Projector 非终态→SUPERSEDED 路径）；T1Test 同步更新。
- **预防措施**：追加事件时先回答"这件事发生在哪个聚合的历史里"。
- **关联**：ST-02、Projector。

### INC-16 —— 观察中（spec gap，非代码 bug）

- **现象**：EX-10 路径下 T0 安全拒绝只记日志、零 Run 落账——与方案"Step FAILED_TERMINAL + 安全事件落账"的表述存在差距（T0 在事务外，尚无 Run/Step 可挂）。
- **前因后果**：T17 实现 EX-10 时如实记录。恶意快照被拒本身工作正常（双路径拒绝均验证），缺的是拒绝事实的账本化。
- **根因**：T0 在 T1 之前执行（事务外），拒绝发生时领域对象尚未创建，安全事件无归属流。
- **解决方案**：M1 做 webhook_inbox 时把"T0 拒绝事件"挂到 inbox/PRSubject 级事件流；M0 接受日志+artifact 登记作为最小留痕。
- **预防措施**：M1 方案评审时显式处理。
- **关联**：EX-10、P1、M1 范围。

### INC-17 —— 已修复（T18 部署暴露，IT 盲区）

- **现象**：compose 栈首次端到端冒烟时，publisher 读 control 落盘的 CAS payload 报权限拒绝，命令 FAILED_TERMINAL（PAYLOAD_UNAVAILABLE）。
- **前因后果**：`LocalCasArtifactStore` 用 `Files.createTempFile` 产出的临时文件默认权限 600（uid 10001），publisher 容器 uid 10002 跨容器读共享卷被拒。单测/IT 全部同进程同用户运行，天然覆盖不到——只有真实双容器部署才暴露。
- **根因**：CAS 的"content-addressed 共享读"语义在编码时只考虑了 digest 寻址，没考虑跨 uid 的文件权限位；测试环境权限同质性掩盖。
- **解决方案**：CAS move 后显式 `setPosixFilePermissions 444`（Windows 非 POSIX 回退）；另配套修复卷属主顺序依赖——两 Dockerfile 统一 `chmod 1777 /var/cas`（sticky 位防互删，挂载顺序无关）。回归：DP-05 常驻冒烟。
- **预防措施**：跨进程/跨容器共享的文件产物必须显式声明权限位；"同进程测试绿 ≠ 多进程部署绿"写入编码自查清单。
- **关联**：DP-05、B16、T18。

### INC-18 —— 已修复（T18 部署暴露）

- **现象**：compose secrets 短语法默认 target 无 `.pem` 后缀，publisher 启动 `NoSuchFileException /run/secrets/github-app-key.pem` 崩溃循环。
- **前因后果**：publisher 配置要求私钥路径 `/run/secrets/github-app-key.pem`，compose secrets 短语法落盘文件名是 key 名本身（无后缀）。启动即崩，日志明确。
- **根因**：对 compose secrets 短语法默认 target 语义的想当然（与 INC-10 同类：未实证依赖工具真实行为）。
- **解决方案**：secrets 改长语法显式 `target: /run/secrets/github-app-key.pem`。回归：DP-01 起栈自检常驻。
- **预防措施**：部署描述文件的每个"默认行为"都要实测一次；smoke-test.sh 的崩溃循环检测即兜底。
- **关联**：DP-01、T18。

### INC-19 —— 已修复（真实模型联调暴露）

- **现象**：mall_R 真实 PR #1（故意埋 5 类 bug）评审闭环机械成功（Check/Review 恰好一次落 GitHub），但 review_finding=0、review 正文为空——stats 显示模型实际返回 6 条 finding，全部被 FindingMapper 丢弃（dropped=6, malformed=0）。IT 的 ST-06 用 mock 模型（verbatim 引用）全绿，真实 qwen-plus 输出却 100% 过不了两级锚定。
- **前因后果**：M0 设计"不信模型行号、用 existing_code 片段工程锚定"，但喂给模型的是 diff 格式文本，模型回摘的片段极可能带 diff 行前缀（+/-/空格）或轻微改写；两级匹配（精确子串 + 行级 trim）均未覆盖该污染形态。叠加 MODEL_RESPONSE 未落 CAS（V1 已预留 artifact_type 但代码没写），线上无法取证模型原始输出。
- **根因**：与模型的输出契约只写在测试 fixture 里，没有在 prompt 中显式约束"逐字引用、禁止 diff 标记"；工程防线（锚定）与输入呈现格式（diff）不匹配。这是"mock 绿 ≠ 真实模型绿"的第二次印证（第一次是 INC-11/12）。
- **解决方案**：① locate() 增加 diff 前缀归一化层级（片段各行剥 `+`/`-`/前导空格后再匹配）；② 文件路径容忍 `a/`、`b/` 前缀差异；③ prompt 显式约束逐字引用规则；④ MODEL_RESPONSE 落 CAS + artifact 登记（可取证）；⑤ 新增污染片段单测。
- **预防措施**：凡"模型输出→工程校验"的契约，必须同时存在 prompt 约束 + 防御性归一化 + 真实模型回归（不能只用 mock 验收）；模型原始响应一律落 CAS。
- **关联**：T08、ST-06、UT-05。

### INC-20 —— 已修复（V3 迁移实库暴露）

- **现象**：V3 在 195 实库执行失败——`ck_publication_resource_state` 旧 CHECK 约束未删就插入新枚举值，校验冲突。
- **前因后果**：V3 初版按"加新约束→改数据→删旧约束"顺序写，PostgreSQL 对既有行立即校验新/旧约束，旧约束不认 `PRESENT/MISSING` 新值。
- **根因**：想当然认为 CHECK 约束只拦新写入；实际上改名/改枚举区间的标准顺序是 drop 旧约束 → update 数据 → add 新约束。
- **解决方案**：V3 改为先 `DROP CONSTRAINT`、再 `UPDATE` 观测态映射（ACTIVE→PRESENT、DRIFTED→MISSING）、最后 `ADD CONSTRAINT`。
- **预防措施**：迁移脚本一律先在沙箱容器按 V1→…→Vn 顺序真跑（本条已固化为 T01 工序），再进运行栈。
- **关联**：M1-T01、V3__m1_inbox_reconcile.sql。

### INC-21 —— 已修复（V3 迁移实库暴露，M0 遗留数据）

- **现象**：V3 建 `uq_review_run_active_gen` 部分唯一索引失败——实库存在 11 个同 `(pr_revision_id, generation)` 的 REVIEW_COMPLETE 活跃 Run（M0 联调反复重放的真实残留）。
- **前因后果**：M0 真实联调为复现 INC-19 多轮重放同一 PR，旧 Run 未被 SUPERSEDED（M0 无收敛唯一约束，靠应用层串行假设）；V3 引入 DB 级兜底索引时被存量脏数据挡住。
- **根因**：M0 应用层"同世代只一个活跃 Run"只是惯例不是约束；真实联调的非常规操作（手工重放）绕过了惯例。
- **解决方案**：V3 内加一次性规整 UPDATE——每 `(pr_revision_id, generation)` 组保留 `created_at` 最新者，其余置 SUPERSEDED，然后再建索引；迁移在 195 实库一次通过。
- **预防措施**：凡应用层不变量，能在 DB 落唯一/检查约束的尽早落；联调期的手工重放要在事后核对账本形态。
- **关联**：M1-T01、ST-21（该索引是 ST-21 双源并发的最后一道防线）。

### INC-22 —— 已修复（195 部署暴露，IT 盲区）

- **现象**：control 部署后 `InboxProcessor.runOnce` 每 5 秒刷 `PSQLException: syntax error at or near "RETURNINGdelivery_id"`。
- **前因后果**：`PostgresWebhookInboxRepository.CLAIM_SQL` 用文本块 `RETURNING""" + SELECT_COLUMNS` 拼接，文本块内容不以换行开头（附带缩进剥离），拼出 `RETURNINGdelivery_id`；本机无 docker，claim 路径的 IT（CT-12/13/15/17）从未真跑，单测不触 SQL 字符串本身。
- **根因**：Java 文本块拼接边界丢失空白 + "本机无 docker → SQL 层 IT 全跳过"的验证盲区叠加。
- **解决方案**：`RETURNING` 后显式换行再拼列清单；全库 grep 同类 `""" +` 拼接，确认仅此一处（`PostgresReviewRunRepository` 用 `\s` 转义无问题）。本地全 reactor 443 复跑绿后 scp 单文件 + 195 重打包重建镜像，control 日志连续轮询周期无错。
- **预防措施**：195 侧 `mvn verify`（挂 docker.sock 真跑 IT）纳入部署门禁，不得仅凭本机单测绿就部署；文本块拼接 SQL 一律在拼接边界显式 `\s` 或换行。
- **关联**：M1-T02、M1-T09a。

### INC-23 —— 已修复（M1-T09a 部署门禁暴露，测试脚本/stub 保真度问题，非产品 bug）

- **现象**：DP 门禁首跑 75 PASS / 2 FAIL。FAIL①：DP-12 "DriftReconciler 重启后未再启动"；FAIL②：DP-14 "stub 收到 check-runs POST 恰好 1 次，实际=2"。
- **前因后果**：FAIL② 初判为"崩溃落在效果已发未确认窗口→RECONCILING 重发"（设计内行为），但取证 dp14-stub-checks.json 发现两条 POST 的 external_id 分别是 DP-05 与 DP-14 的命令——journal 清零从未生效：脚本沿用 WireMock 2 时代的 `POST /__admin/requests/reset`，3.13 已 404，curl -s 静默吞掉。M0 的 DP-05 通过纯属侥幸（当时 stub 容器新起 journal 本为空）。FAIL① 是 `publisher_started()` 匹配全量日志被首次启动旧标记骗过，wait_for 瞬时返回，sleep 5 不够重启启动完成。
- **根因**：① 对工具 admin API 版本的想当然（与 INC-10/INC-18 同族：未实证依赖工具真实行为）；② 测试脚本用"日志含标记"当等待条件，对重启场景不成立。
- **解决方案**：journal 清零改 `DELETE /__admin/requests`（实测 200，journal 24→0，mappings 12 条无损）；DP-12 publisher 等待改计数式（重启前标记数+1）；DP-14 重构为确定性窗口——202 落库即 SIGKILL（崩溃窗口 1：已受理未处理），去掉"等 PROCESSING"（该窗口亚秒级，不可稳定命中）与手工拨租约的 hack，PR 号随机化保证脚本可重跑。
- **预防措施**：stub/工具的 admin API 调用必须实证响应码，禁止 `-s` 吞错后默认成功；测试等待条件一律用"可区分的增量"（计数/新 id），不用全量日志匹配。产品侧复核结论：崩溃恢复链路账本正确（无重复 outbox 命令、publication_resource 唯一约束生效），无需改代码。
- **关联**：M1-T09a、DP-05/12/14；stub probe-list 恒空的保真度局限已记录（真实 GitHub 下 RECONCILING 探针可认领已建资源，stub 下会重发——交由测试 agent 的故障注入用例按需覆盖）。
  - **复跑追加（同日第二轮）**：修复后又暴露两个脚本自身缺陷——① DP-05 固定 PR#7 重跑命中同 revision 去重（不重审已评审快照是产品正确行为），断言却期待新 outbox；② DP-14 重构后漏加 outbox 收敛等待，inbox PROCESSED（=T1 完成）不代表异步评审+发布完成，即时断言假性 FAIL。处置：DP-05/DP-14 PR 号随机化且区间错开（100~599 / 600~899），DP-14 补 `wait_for outbox 两条 CONFIRMED`。教训入档：**冒烟脚本自身必须可重跑（幂等），断言前必须有收敛等待**。

### INC-24 —— 已修复（DP-13 真实联调暴露，M1 代码缺陷）

- **现象**：真实 draft PR 事件落 inbox 后权威读连续失败：`authoritative_read_retry / forbidden`，重试 4/5 次。
- **前因后果**：M1 新增"GitHub 权威读"（GET /pulls/{n}）与 DriftReconciler 探针（GET /check-runs/{id}）都走 publisher 窄接口签发的 `TokenScope.READ` token，但 M0 的 READ 定义只有 `contents:read`——M0 时代只读 tarball（contents 足够），M1 的 pulls/check-runs 端点分别要 `pull_requests:read`/`checks:read`，缺权被 GitHub 拒。stub 不验权限所以 IT/DP 全绿，真实联调才暴露（"stub 绿 ≠ 真实绿"第三次印证）。
- **根因**：新增读路径时没有同步审计凭证 scope 的权限映射；stub 环境不建模权限语义，形成验证盲区。
- **解决方案**：`READ → contents:read + pull_requests:read + checks:read` 只读三元组（仍是零写权限，不违反最小权限原则）；两处单测期望同步更新；本地全 reactor 绿后重建 publisher。inbox 第 5 次重试自动成功（PROCESSED，projection draft=t，零 Run——退避重试机制按设计工作，无需人工重投）。
- **预防措施**：凡新增触网端点，必须同步审计"该端点所需的最小 GitHub 权限"并更新 TokenScope 映射；交接文档/测试矩阵中凡涉及真实模式的用例都要覆盖权限维度。
- **关联**：M1-T05/T08、DP-13；证据 F-3（404 隐藏私有资源）同族权限语义。

### INC-25 —— 已修复（DP-13 暴露，部署配置回归 + 工具操作坑）

- **现象**：GitHub 创建 draft PR 后 webhook 投递 502（status: "failed to connect to host"）；用 API 重投时首次 404。
- **前因后果**：① M0 真实联调期把服务器上 compose 的 control 端口改为公网绑定，但该改动没回写仓库（仓库版仍是 `127.0.0.1:` 硬编码）；M1 tar 同步用仓库版覆盖了服务器文件，公网入口消失。② 首次重投 404 是 jq 把 delivery ID（19 位整数）按 float64 处理丢精度（...925664 → ...539300）。
- **根因**：① "服务器现场改配置不回写仓库"的漂移惯例——凡 tar 全量同步都会冲掉未入库的服务器侧修改；② jq 大整数精度陷阱。
- **解决方案**：compose 端口绑定 env 化（`${CONTROL_BIND:-127.0.0.1}`，默认保持 loopback 安全底线），真实模式 .env 与 .env.realmode.bak 均写入 `CONTROL_BIND=0.0.0.0`；重投用原始 JSON 文本取精确 ID 后成功（202，事件落 inbox）。
- **预防措施**：服务器侧任何配置修改必须当天回写仓库或 .env 模板；处理 GitHub 大整数 ID 一律用字符串/grep 原文，不经 jq 数值化。
- **关联**：M1-T09a、DP-13；M0 PROGRESS 中"control 8080 改公网发布"一行即当时未回写的现场修改。

### INC-26 —— 已修复（DP-13 真实联调暴露，M1 代码缺陷 + 单测编码错误预期）

- **现象**：DP-13 断言⑤——PR#2 close（epoch 1→2）后 reopen，新 Run 建了但 `publication_epoch` 停在 2（应为 3）；旧世代语义未被切断。
- **前因后果**：`PrEventAuthoritativeReader` 对 `reopened` action 没有任何特判（全文零命中），reopened 走普通 FullReview 路由；而 T1 的换届判定只在 revision/policy 变化时 bump epoch——head/base 未变的 reopen 自然不换届，违反方案 §4.4"I15/ST-20：reopened 即使代码未变也新 epoch 新 Run（换届是状态语义不是 diff 语义）"。更隐蔽的是：单测 `reopenedWithUnchangedCodeYieldsFullReview` 本身就编码了错误预期，ST-20 的 IT 因本机无 docker 从未真跑——"单测绿"反而掩盖了方案违背。
- **根因**：决策表七值里漏了 Reopen 这一值；"close 是状态语义要换届"在代码里落实了，对称的 reopen 被默认归并到 FullReview 路径，方案评审与编码都未发现不对称。
- **解决方案**：① `PrRouteDecision` 新增 `Reopen(FetchResult.Found)` 决策值；② Reader 在 draft 分支后、收敛点前判 `action=reopened` → Reopen；③ InboxProcessor 新增 case：先 `orchestrator.reopenGeneration(...)`（幂等守卫：已 OPEN 非 draft 只刷投影不 bump，重放安全）再以远端权威值走 T1 dispatch，换届与 T1 分两笔事务；④ PrStateReconciler 补同 case（探针 action 恒 synchronize 不可达，防御性按 FullReview）；⑤ 修正编码错误预期的单测 + 新增 Reader/InboxProcessor/Orchestrator 三层 reopened 用例（含重放不双 bump）。本机 242+106 全绿。
- **验证**：195 重部署后真实 close→reopen 重验：close epoch 2→3（CLOSED），reopen（代码未变）epoch 3→4（OPEN）+ 新 Run REVIEW_COMPLETE + 2 条 outbox CONFIRMED（同 head Revision 复用，秒级完成属预期）。DP-13 五断言全部通过。
- **预防措施**：对称语义（close/reopen、freeze/unfreeze）必须成对检查实现与测试；方案决策表的每个枚举值都要有对应的"该值存在"的静态/行为断言；195 真跑 IT 入部署门禁（本 bug 的回归测试 `st20_reopenedWithUnchangedCodeGetsNewEpochAndNewRun` 正是漏跑的那条）。
- **关联**：M1-T05/T06、DP-13、ST-20、I15；与 INC-22 同族（IT 盲区），与 INC-24 同批暴露。

### INC-27 —— 已修复（195 首跑 M1 IT 暴露，两条 IT 测试缺陷，非产品 bug）

- **现象**：195 挂 docker.sock 首跑 `mvn verify`，control IT 22 条中 2  FAIL：① `InboxProcessorIT.st09` 断言重投返回 200 duplicate，实际 202；② `AuthoritativeRoutingIT.st12` 断言 draft 4 事件 fetchCalls=4，实际=2。
- **前因后果**：① st09 在处理前重投——inbox 行还是 RECEIVED（在途），按 RedeliveryDecision 设计应答 202 {"status":"processing"}（如实回放"处理中"），测试却断言只有终态重投才该有的 200 duplicate。② st12 一次插入 4 条 inbox 再一把 `runOnce()` 批量 claim——`UPDATE ... WHERE id IN (SELECT ... ORDER BY ...) RETURNING` 的 RETURNING 顺序不受子查询 ORDER BY 保证，d4 先于 d2/d3 被处理时水印推进到 T4，旧事件被 StaleEventGuard 正确判 STALE 零 API——这正是 LWW 防线的设计行为，测试却假设严格按序处理。
- **根因**：两条 IT 从未真跑（本机无 docker），断言建立在"批处理保序"和"重投即终态"两个未经验证的假设上；单测层面这两处语义分别有 RedeliveryDecisionTest/StaleEventGuardTest 覆盖且正确，是 IT 编排错了。
- **解决方案**：① st09 改为两段式断言——处理前重投→202 processing，处理后重投→200 duplicate（顺带把在途重投语义钉进回归）；② st12 改为插入一条处理一条，顺序确定，"每次 draft push 一次 GET"的语义断言不变。产品代码零改动（行为经核对均属设计内）。
- **预防措施**：涉及"应答语义""处理顺序"的断言必须先核对实现契约再写；批处理场景不得假设 RETURNING/消费顺序，要么逐条喂要么断言乱序鲁棒性；195 真跑 IT 已证实为不可替代的验证层（本族第 4 次：INC-22/24/26/27）。
- **关联**：M1-T03/T05、ST-09、ST-12；DP-13 重验通过后首跑 verify 暴露。

### INC-28 —— 已修复（测试执行回流 TB-03，smoke 脚本缺陷，非产品 bug）

- **现象**：DP-02（给 control 注入写凭证 → 拒启）第 4 断言 FAIL：`dp02-control 稳定运行中（State=running RestartCount=0），自检门失效`。
- **前因后果**：测试执行 agent 手工复现取证（0.2s 轮询 + 5s/15s 复查）证实：自检正确检出写凭证、抛异常、优雅关停约 300ms、最终 exited/ExitCode=1——产品行为完全正确；脚本在"检出失败日志→立即 inspect"窗口内采样到 running 属竞态。且脚本注释假设"compose run 继承 unless-stopped、崩溃循环重启、RestartCount>0 兜底"不成立——`docker compose run` 一次性容器不继承 restart 策略。
- **根因**：对 compose run 行为的想当然（INC-10/18/23 同族：未实证工具真实语义）+ 用瞬时采样断言一个需要等终态的性质。
- **解决方案**：DP-02 改轮询等终态（`State=exited`，30s 超时）+ 断言非零退出码；修复后 195 复跑 DP 门禁 77/77 全绿（证据 smoke-evidence/20260831-105403）。
- **预防措施**：凡断言"进程应死亡/应拒启"类性质，一律轮询等终态 + 验退出码，不做瞬时采样；注释里关于工具行为的假设必须实证（本条由测试 agent 的手工复现取证驱动定位，故障卡六要素的价值兑现）。
- **关联**：DP-02、TB-03；首次"执行 agent 取证 → 主会话修复"回路完整走通。

### INC-29 —— 已修复（测试执行回流 TB-06，smoke 脚本缺陷，非产品 bug）

- **现象**：DP-01 第 2 断言 FAIL：期望 `dp01-migrate.log` 含 `[Migrating schema]`，实际为 `Schema "public" is up to date. No migration necessary.`（退出码断言 PASS）。
- **前因后果**：E2E-24 设计内动作 `docker compose down/up` 使首启 migrate 容器被销毁重建，之后任何轮次的迁移日志恒为 "up to date"——断言永久失效。主会话 10:54 轮（pristine 栈）77/0 与执行方 11:33 轮 76/77 的差异全部由此解释。产品与迁移机制本身无缺陷（flyway_schema_history 至 V3 完好）。
- **根因**：断言依赖"首启容器日志存续"这一环境态而非数据态——与 TB-03/INC-28 同族：把一个会随测试流程合法变化的环境性质当成了不变量。
- **解决方案**：DP-01 日志断言改双态（首启 `Migrating schema` / 稳态 `up to date` 均 PASS），并新增真正门禁：断言 `flyway_schema_history` 已应用最大版本 == `control-app/src/main/resources/db/migration/V*.sql` 文件最大版本（V4 落地后脚本零改动）。
- **预防措施**：部署门断言优先断言数据库/账本等持久态事实，日志文本断言仅在语义稳定时使用；含 `compose down` 的用例（E2E-24）会销毁一切"首启痕迹"，后续断言设计必须考虑这一顺序耦合。
- **关联**：DP-01、TB-06、E2E-24；回归测试 = DP-01 本身（修复后复跑待执行方全量回归确认）。

### INC-30 —— 已修复（外部评审对账 #25，FindingMapper 歧义锚定，产品缺陷）

- **现象**：Finding 锚定时，若模型给出的 `existing_code` 片段在文件中出现多次（精确子串层或行级 trim 层），旧实现取第一次命中定行号——锚定结果取决于文本顺序而非唯一性，可能把 Finding 挂到错误位置。
- **前因后果**：评审清单 #25 指出"片段多次出现时不能猜行号"；对账确认 FindingMapper 两级匹配（精确子串 → 行级 trim 退化）均无唯一性校验，属部分覆盖的真缺口。
- **根因**：锚定逻辑只实现"找得到"，没实现"找得唯一"——多重命中在语义上是歧义而非定位，不应被静默解释成第一个。
- **解决方案**：两级匹配均加唯一命中要求——精确子串层 `indexOf(snippet, idx+1)>=0` 即返回 null；行级 trim 层第二次命中即返回 null（Finding 丢弃并计 dropped）。类 javadoc 同步；`FindingMapperTest` 新增 3 条（歧义精确/歧义 trim/唯一仍定位），本地 18/18 绿。
- **预防措施**：凡"按内容定位"的逻辑必须区分"未命中/唯一命中/多重命中"三态，多重命中默认丢弃并留计数证据；不确定时降级（丢弃）优于猜测。
- **关联**：外部评审 #25、FindingMapper、FindingMapperTest::inc29_*（注：测试方法名沿用对账阶段编号，INC 编号以此条为准）；回归测试 = 该 3 条新增用例。

### INC-30 —— 已修复（测试执行回流 TB-07，M0 遗留 I17 违例，潜伏缺陷）

- **现象**：E2E-21 FAIL——`WorkItemWorker.claimNext/findExpiredLeases` 把应用侧 `Instant.now()` 作为 SQL `:now` 参数参与 work_item 租约/过期比较（`PostgresWorkItemRepository` 六条 SQL），违反 I17"一切过期/退避比较走 DB now()"的字面。（另更正：第二轮"应用时钟零命中"是执行方 grep 过度转义的假阴性。）
- **前因后果**：M0 编码早于 I17 诞生（I17 是 M1 二审 E2E-21 引入的不变量），旧代码未被回溯核查；当前部署应用与 DB 同宿主机、时钟同源，实测偏差≈0，属潜伏态——但风险条件（应用与 DB 时钟域分离）在 M7 多实例/跨机时必然兑现。执行方行为级补验证实 lease_epoch 栅栏对接管危害的缓解有效（租约期内十轮零提前接管，过期 37s 内 epoch 推进重认领）。
- **根因**：不变量新增后未对存量代码做全量回溯审计（M0 路径漏网）；比较时间戳与业务时间戳在签名上同型（Instant），编译器无法区分，靠人工纪律守不住。
- **解决方案**：work_item 家族六条 SQL 的比较与 `updated_at`/`lease_until` 写入全部改 DB `now()`/`make_interval`；port 六方法签名摘除应用时钟参数（heartbeat 改传租约秒数）；InMemoryStores fake 引入可设置时钟承接原时间旅行测试；调用方（WorkItemWorker×3、ReviewOrchestrator×4、publisher ItHarness×1）同步。新增 `PostgresWorkItemRepositorySqlGuardTest` 纯文本守卫（六条 SQL 禁 `:now`）。主会话全 reactor 亲验：98+249+107 全绿。
- **预防措施**：新增不变量必须带"存量代码回溯审计"动作；SQL 文本守卫单测把 I17 变成编译期后仍有保障的红线；`PrStateReconciler:141/292` 内存节奏门裁定豁免留档（M7 多实例时需改 DB 后盾，已记 M2 方案 §8 观察项）；`PostgresPRSubjectRepository` 的 `:now` 属 updated_at 写入非比较，不在 I17 字面范围，留档观察。
- **关联**：TB-07、E2E-21、I17；修复后待执行方全量回归确认。

### INC-31 —— 已修复（M2-T09 测试矩阵反查，checkpoint 契约诊断缺口）

- **现象**：checkpoint 仅持久化五分量合成 digest；契约变化时只能记录笼统 `CONTRACT_CHANGED`，无法满足 UT-18/EX-22 要求的精确变化分量。
- **根因**：首次实现只把“复用安全性”落库，遗漏了“可诊断性”同样是冻结契约的一部分；单一不可逆 digest 无法事后判断是哪一分量变化。
- **解决方案**：V4 `step_checkpoint` 同时持久化 prompt/schema/mapper/context/model identity 五个版本值；恢复时按固定优先级给出 `CONTRACT_CHANGED:prompt|schema|mapper|context|model_identity`，合成 digest 仍作为快速总校验。新增 6 组合参数化回归。
- **预防措施**：凡方案要求“精确 reason”的摘要校验，都必须同时设计可解释元数据，不能只留不可逆总摘要。
- **关联**：M2-T03/T04、I18、UT-18、EX-22。

### INC-32 —— 已修复（M2-T07 收口审计，repair lineage/终态缺口）

- **现象**：首版 RepairPlanner 创建的零 Step REPAIR Run 会永久停在 CREATED；同时 CHECK_RUN 最新命令若是 UPDATE_CHECK，直接改铸 CREATE_CHECK 会缺少原 CREATE 的 `head_sha/name`，无法按最新 completed 终态重建。
- **根因**：实现只覆盖“发出修复命令”，没有沿 request 终态反向收口独立 Run；desired payload 误按“单条最新命令”理解，忽略 UPDATE 是增量而 CREATE 含远端身份基线。
- **解决方案**：publisher 先投影 request 终态，control 再用行锁把 REPAIR Run 从 CREATED 直接收口为 COMPLETED/FAILED 并追加可重放 RUN_STATE_CHANGED；RepairCandidate 同时携带最早 CONFIRMED CREATE 基线与最新 CONFIRMED payload，Factory 先铺基线再覆盖最新终态并剥离旧远端身份。新增 Run 收口、Projector fold、Factory 合并和 ST-31 真栈用例。
- **预防措施**：零 Step Run 必须显式设计终态来源；“最新期望状态”测试必须包含 CREATE→UPDATE→丢失，不能只测 CREATE→丢失。
- **关联**：M2-T06/T07、I27、CT-28、ST-31。

### INC-33 —— 已修复（M2-T08 边界用例补齐，marker 歧义）

- **现象**：Review 探针在多个对象命中同一 marker、或单个正文重复 marker 时取第一个对象，可能对错误远端对象做“已存在”判定。
- **根因**：探针只实现“找到一个”，未区分唯一命中与歧义命中，违背 I20 的 UNKNOWN fail-closed 规则。
- **解决方案**：`PublishReviewHandler` 对多对象命中和正文重复 marker 一律返回 `ProbeResult.Unknown(ambiguous_review_marker)`；`ProbeResult.FoundWithContent` 构造期强制 digest 非空。新增 marker 剥除/重复/多对象及逐字节 digest 回归。
- **预防措施**：所有幂等探针统一按未命中/唯一命中/歧义三态设计，歧义不得任选。
- **关联**：M2-T08、I20、UT-24/25、EX-27。

### INC-34 —— 已修复（M2 编码评审 RM2-02，授权矩阵方案级漏洞）

- **现象**：V4 初稿给 publisher_app 的是 repair_request **整行 INSERT**——该角色可直插 `state='APPROVED'` 并自填审批三列（伪造人工批准），或直插 DISPATCHED 跳过 RepairPlanner 全链路；而领取口 APPROVED 无条件可领，链条闭合后 REVIEW 类资源（恒 MANUAL）可被 publisher 单点自动修复。
- **前因后果**：M2 编码评审（四切片只读评审）发现；实现照方案 §4.1 字面执行，**洞在方案层**。威胁先例：V2 给 publisher 零 outbox INSERT 的理由正是"不能伪造写意图"——伪造审批意图与之同构。
- **根因**：授权矩阵设计时只考虑了"铸单"正当需求（DriftReconciler 同事务 INSERT），未审 INSERT 面的伪造向量；列级授权只做了 UPDATE 侧，INSERT 侧漏了。
- **解决方案**：列级 INSERT（排除 approved_*/repair_run_id/repair_operation_id）+ BEFORE INSERT trigger 强制初始 `state='PENDING'` 且审批三列恒空；方案 v1.2 授权矩阵文本同步修订。
- **预防措施**：授权矩阵评审增加固定检查项——"该角色能否伪造意图/审批/身份"（INSERT 面与 UPDATE 面分别审）；CT-29/DP-15 真库断言越权 INSERT 被拒。
- **关联**：RM2-02、I21、CT-29、DP-15、方案 v1.2。

### INC-35 —— 已修复（M2 编码评审 RM2-04，M1 语义回归 + Fake/PG 分叉）

- **现象**：REVIEW 类资源（带 contentDigest）MISSING 后若远端被人工恢复，巡检探测 FOUND 时只走 markContentDrift/clearContentDrift——两者 PG 端 `WHERE state='PRESENT'` 对 MISSING 行 0 更新：资源永不归 PRESENT、next_check_at 不重排，**每轮巡检都重复探测**，直到人工干预。
- **前因后果**：M1 契约明确承诺"MISSING 复核找回归 PRESENT"（PublicationStore:130 注释、DriftReconciler:47 类注释）；M2 引入内容漂移分支时把 digest 非空资源的 FOUND 处理整段切走，归位语义被顺带丢掉。掩盖因素：`FakePublicationStore.clearContentDrift` 会顺带 flip 成 PRESENT 且不复刻 PG 守卫——Fake 与 PG 语义分叉，单测因此全绿。
- **根因**：M2 改 FOUND 分支时只考虑"内容漂移 episode"新语义，没有回查 M1 的归位契约；Fake 的"与 PG 相同守卫语义"自称无机器校验背书。
- **解决方案**：PG 端 markContentDrift/clearContentDrift 守卫放宽到 `state IN ('PRESENT','MISSING')` 且 SET 归位 PRESENT + 重排 next_check_at（digest 一致=纯归位+清 episode，不一致=归位+开 episode）；Fake 三处守卫对齐 PG；PublicationStore javadoc 补 MISSING 归位语义。新增 DriftReconcilerTest 两条回归（digest 一致归位零事件 / 不一致归位+episode 恰一次）。
- **预防措施**：改共享分支（如 handleFound）时先列出该分支服务的全部既有契约逐条回归；Fake 与 PG 的守卫谓词必须逐字对齐，分叉视同测试失效。
- **关联**：RM2-04、§4.4、I26、ST-36、DriftReconcilerTest::missingReviewFound*。

### INC-36 —— 已修复（M2 编码评审 RM2-03，CreateCheckHandler 探针歧义漏网）

- **现象**：CreateCheckHandler.interpretProbe 循环内首个 marker 命中即 return，无第二命中检测；M2 新增的 probe-first 短路把 FOUND 直接兑现为零写 CONFIRMED——歧义场景会认领首个命中对象（review 被引用回复时隐藏 marker 被复制，双命中现实可达）。方案 R-R5 声称"多 marker 命中已转 UNKNOWN 不任选"，实际只有 PublishReviewHandler 做到了（INC-33），CreateCheckHandler 漏网。
- **根因**：INC-33 修复只覆盖了一家 Handler；同族逻辑（探针循环）没有全库清查的习惯动作。
- **解决方案**：CreateCheckHandler 探针改收集全部命中——0→NotFound、恰好 1→Found、≥2→Unknown(ambiguous_check_marker)；UpdateCheckHandler 为单资源 GET 无枚举面，核查无需改。EX-27 补 IT 形态钉死。
- **预防措施**：修一类缺陷时必须 grep 同族实现（"探针""迁移""守卫"按模式全库找）；方案 R-R* 诚实清单的"已转 X"类声明需逐代码核实后才能写入。
- **关联**：RM2-03、INC-33、I20、EX-27、R-R5。

### INC-37 —— 已修复（M2 编码评审波次 2 发现 + 用户裁定，attempt_count 终态不打满）

- **现象**：RepairDispatchService.fail() 在"可重试且预算耗尽"时直接 markFailedTerminal **不递增 attempt_count**——终态单显示 4/5 而非 5/5；markRetryWait 的预算翻转分支从 Planner 链路不可达（死分支）。
- **根因**：终态路径与重试路径的计数责任划分不清；无测试钉"耗尽时计数打满"语义。
- **解决方案**（用户裁定修）：预算耗尽的 retryable 终态路径把 attempt_count 递增打满；非 retryable 坏 payload/CAS 缺失终态不递增（不烧预算语义保留，CT-27 已钉）。CT-27 断言同步 5/5。
- **预防措施**：涉及"计数/预算"的终态必须单测钉住终态值；死分支要么删要么接通，不允许"看起来有防御实际不可达"。
- **关联**：RM2 波次 2 发现、CT-27、方案 v1.2 裁定-2。

### INC-38 —— 已修复（M2 编码评审波次 2 发现 + 用户裁定，EX14 stub 取证盲区，测试缺陷）

- **现象**：M1 的 EX14DriftProbe5xxIT 的 review 探针 stub body 与生产 buildBody 逐字节不等（差换行/格式）——该测试运行期间 review 资源实际持续触发 CONTENT_DRIFTED 告警，但测试从未断言这一面，形成取证盲区（烟雾报警器在叫，没人看报警器）。
- **根因**：测试数据手工拼写，未与生产产出对拍；测试目标（5xx 退避）与副产物（漂移告警）无隔离检查。
- **解决方案**（用户裁定修假数据）：stub body 逐字节对齐生产 buildBody，漂移告警噪声消除；类 javadoc 留痕。EX-23 另补"期望端与探针端 digest 算法同源"对拍，防此类漂移再犯。
- **预防措施**：stub 中的"业务内容型"数据（评论正文、check 输出）必须从生产 builder 导出或对拍，禁止手拼。
- **关联**：EX14、EX-23、方案 v1.2 裁定-3。

### INC-39 —— 已修复（M2 测试首轮回流 TB-10，换届扫描面未排除 REPAIR Run，产品缺陷）

- **现象**：EX-24（Ex24RepairSupersedeRaceIT）首跑 FAIL——repair 命令铸出后 revision 换届，control 侧第二轮 RepairPlanner `runOnce()` 处理 0 行（期望 1），零 Step REPAIR Run 无人收口 FAILED。
- **根因**：`findActiveByPrSubjectId`（M1 写的换届/幂等守卫/账本挂载共用查询）对 run_mode 无甄别，把 V4 新增的 REPAIR Run 也当"在途评审 Run"——换届扫描把它扫成 SUPERSEDED 后，repair 收口查询 `findTerminalRunOutcomes`（要求 `r.state='CREATED'`）永远匹配不上，M2 方案 I27 设计的"EXPIRED→FAILED 收口"成为不可达死路径。M2 编码期未清查该共用查询的全部调用点语义；IT 首跑才暴露（本机无 docker 盲区）。
- **解决方案**：`findActiveByPrSubjectId` 收敛为"活跃**评审** Run"语义（PG SQL 加 `run_mode='NORMAL'`，InMemoryStores fake 同步），REPAIR Run 的终态由 repair 收口链独占；接口 javadoc 明示排除理由。顺带消掉两个潜伏次生害：close/draft 重投幂等守卫（isEmpty 判定）与 ReconcilerDegraded 挂载点不再被滞留在途 REPAIR Run 干扰。
- **预防措施**：新增 Run 类别（run_mode）时必须全库清查"按状态扫描 Run"的共用查询；方案里"A 状态机由 B 组件收口"的收口路径必须有 IT 实证（本例正是 IT 首跑抓到）。
- **关联**：TB-10、EX-24（回归测试）、I27、ST-32。

### INC-40 —— 已修复（M2 测试首轮回流 TB-08，IT 装备 final 类 CGLIB 不可代理，测试缺陷）

- **现象**：ST-26（St26CrashBeforeCheckpointTxIT）初始化即 `AopConfigException: Cannot subclass final class StCheckpointCrashCheckpointWriter`，0.04s 秒杀。
- **根因**：测试替身类声明了 `final`，而线束 `transactionalProxy` 走 CGLIB 类代理（与生产 docker profile 语义一致）——作者写了 javadoc 说明代理意图，却加了 final，本机无 docker 从未真跑，首跑即炸。
- **解决方案**：去掉 `final`，类 javadoc 留痕"不得声明 final（CGLIB 需要子类化，TB-08）"。
- **预防措施**：需要被 CGLIB 代理的测试替身类，在类名/注释上标注代理需求；IT 首跑警告机制已覆盖此类（交接文档已声明首轮建基线）。
- **关联**：TB-08、ST-26。

### INC-41 —— 已修复（M2 测试首轮回流 TB-09，ST-30 等价性断言不可满足，测试设计缺陷）

- **现象**：ST-30（St30CheckpointPathEquivalenceIT）断言"续跑路径与冷路径 Step 产出 digest 相同"失败——两 digest 均为合法 sha256 但不等。
- **根因**：测试编排让 Run X（head-st30-x）与 Run Y（head-st30-y）头不同，而 `finding_fingerprint = SHA256(head_sha|…)`（FindingMapper 明示契约）——digest 不等是**设计内必然**，断言本身就不可满足；测试自己在 outbox payload 对比处已把 head_sha 列为易变键剔除，唯独漏了 Step 产出与 review_finding 两处含 fingerprint 的对比面。
- **解决方案**：Step 产出改为"CAS 回读 + 剔除 findings[].fingerprint 后 JSON 树逐字段比较"；review_finding 逐字段对比剔除 fingerprint 列；javadoc 同步更正。等价性强度不稀释（其余字段仍逐字段全等）。
- **预防措施**：写"全等/等价"断言前先列出产物里所有"身份衍生字段"（fingerprint/digest/时间戳/UUID），逐个裁定剔除或保留；本例若先列清单即可避免。
- **关联**：TB-09、ST-30、FindingMapper fingerprint 契约。
- **续（第二轮复验）**：修复不完整——outbox payload 对比面 `normalizedPayloads` 漏剔嵌套 findings[].fingerprint（执行方二轮回流精确指出），补递归 `stripFingerprints` 供 payload 与 Step 产出两处共用。教训追加：剔除字段裁定必须覆盖**全部对比面**（本例三处：Step 产出、review_finding、outbox payload），首轮只覆盖两处。

### INC-42 —— 待修复（随 M3 I34 关闭；开源调研发现的在役设计缺陷：Spring AI 默认 10 次隐藏重试一直在生效）

- **现象**：M0~M2 在役代码从未设置 `spring.ai.retry.max-attempts`，Spring AI 1.0.0 默认 10 次重试
  （退避 initial 2s、multiplier 5、上限 3min）一直在适配器层生效——模型端点瞬时故障会被静默重试，
  一次逻辑调用最坏 10 次真实 HTTP、拖 20+ 分钟；外层 120s 硬超时（Future.get）实际是唯一的闸。
- **发现路径**：M3 开源实证调研（F-10，Spring AI 1.0 官方文档 Retry Properties）。
- **根因**：M0 引入 Spring AI 时未核对其默认重试面；`ModelClient` 契约假定"一次调用一次 HTTP"，
  但适配器层对此无任何保证。
- **解决方案**：M3 I34/T00 关闭（`spring.ai.retry.max-attempts=1` + WireMock journal 验证恰好 1 条 +
  升级检查单固化底层 SDK `maxRetries(0)`）。M0~M2 期间影响无法追溯（无账本——正是 M3 要补的缺口）；
  真实联调未现异常，但"看起来稳"可能部分来自隐藏重试吞掉瞬时故障，属不可证伪区间，如实留档。
- **预防措施**：引入任何带内置重试/超时语义的框架，其默认值必须显式核对并落配置；
  T00 式"可行性验证先行"已写入 M3 任务拆解（M3-T00 绝对先行）。
- **关联**：F-10、I34、M3-T00、M3 方案 §4.10。

### INC-43 —— 已修复待回归（M2 第三轮回流 TB-11，DP-15/DP-19 授权断言函数与 V4 列级授权形态不匹配，测试脚本缺陷）

- **现象**：DP-15 三条 + DP-19 一条 FAIL——`has_table_privilege('publisher_app','repair_request','INSERT'/'UPDATE')` 实测 f、脚本期望 t；另 DP-15 行为面一条把 psql 多语句输出与 `PENDING|<null>|<null>` 逐字全等比对，必然失败（多语句 `-c` 回显 BEGIN/INSERT 0 1/ROLLBACK 命令标签）。
- **根因**：V4 对 publisher 授的是**列级** INSERT/UPDATE（RM2-02 修复的落点），PG 语义下列级授权不提升表级 ACL，`has_table_privilege` 恒 f——断言函数选型与授权形态不匹配；同段 10 条 `has_column_privilege` 列级断言全 PASS，反证授权面本身正确。写断言时把"有权限"直觉映射到了表级函数。
- **解决方案**：表级断言改断"f"（列级授权不提升表级 ACL 恰是设计意图，f 才是正确形态）；逐字比对改 `grep -qx` 全行匹配结果行；DP-19 同步改列级断言（`has_column_privilege(...,'state','INSERT')`）。
- **预防措施**：断言 PG 权限前先确认授权形态（表级/列级）再选函数——`has_table_privilege` 与 `has_column_privilege` 不互换；psql 多语句输出的断言一律 grep 关键行，不做整串全等。
- **关联**：TB-11、RM2-02（V4 列级授权设计）、DP-15/DP-19。

### INC-44 —— 已修复待回归（M2 第三轮回流 TB-12，stub 固定创建 id 撞 uq_pub_resource 致资源登记被静默吸收——stub 保真度结构性缺陷）

- **现象**：DP-18 九条连锁 FAIL——基线闭环 CONFIRMED=2 正常，但按 `created_by_operation_id` 查不到 check 资源行：stub 静态映射对任意 POST /check-runs 恒回 id=7000001，与库内 M1 遗留行 `CHECK_RUN|7000001|MISSING` 撞 `uq_pub_resource(resource_type,remote_id)` 唯一索引，登记走 `ON CONFLICT DO NOTHING` 被静默吸收。修复闭环（E2E-28 门禁化目标）实际从未被验证到，repair_request 累计 0 行。
- **根因**：stub 固定创建 id × 唯一索引幂等吸收——**不是一次性脏数据**：即使清掉遗留行，每轮基线创建仍撞上一轮自己的残留行，结构性复发。M1 期 TB-04 已裁定同族现象为 stub 保真度伪影，但当时未除根。
- **解决方案**（三件套，产品代码零改动——ON CONFLICT 幂等吸收是 I26 设计内行为）：① 一次性删除两行遗留资源行（DELETE 2，repair_request 零引用，主会话在 195 执行）；② stub 静态创建映射改 response-template 随机唯一 id（check 7200000-7499999 / review 8100000-8999999；compose 加 `--local-response-templating`，仅声明 transformer 的映射启用）——基线登记从此不撞库，195 实证连续两次 POST 返回互异 id；③ 脚本去硬编码：DP-18 旧行 remote_id 断言改基线捕获值；E2E-29/30A/32B 探针预注册 id 与延迟响应 id 同源唯一（7150000-7199999 号段）；`m2_post_check_delay_on` 缺省 id 改随机；号段纪律写入 m2-lib.sh 文件头。
- **预防措施**：stub 替身凡是"创建对象"的响应，id 必须随机/唯一——固定 id × 唯一约束 × 幂等吸收 = 静默伪绿温床；测试栈引入新"创建类"映射时按 m2-lib.sh 号段表取号。
- **关联**：TB-12、TB-04（同族前轮）、I26、DP-18/E2E-28。

### INC-45 —— 已修复待回归（M2 第四轮回流 TB-13，INC-44 次生面：stub 探针无状态致 drift-repair 无限重建风暴，测试桩保真度结构性缺陷）

- **现象**：阶段 C 7 败（E2E-32A×1 + E2E-33×6）；`repair_request` 128→553、`publication_resource` REPAIRED 552 持续增长（~15s 周期）；E2E-33 显式 id override 映射被风暴重建行抢占撞 `uq_pub_resource` 静默吸收 → 链断。
- **根因**：INC-44 把 stub 创建响应改随机 id 后，脚本无法再预注册探针可见映射（id 不可预知）；stub 是无状态替身，**重建成功的对象对探针恒不可见** → 下轮巡检必判 MISSING → AUTO 修复 → 重建 POST → 仍不可见 → 无限循环。真实 GitHub 无此病态（重建即可见）——stub 缺"创建即可见"的最小状态性。产品逻辑在"探针恒空"世界里行为自洽，判非产品缺陷。
- **解决方案**（probe-sync 机制，全部落在 `deploy/m2-lib.sh` + 三个脚本挂接，产品代码零改动）：① 后台守护（`m2_probe_sync_start`）轮询 stub journal 的 `POST /check-runs`、`POST /pulls/{n}/reviews`，按 external_id/marker 回查 DB 取 remote_id，即时登记探针可见映射（priority 1，`metadata.m2ProbeSync` 标记）；② 探针映射状态从进程内数组迁到状态文件（`${M2_EVIDENCE}/.probe-sync/`，flock 互斥），`m2_check_present_add/remove`、`m2_review_present_set/clear` 签名不变、内部改走状态文件——调用点零改动；③ 换装一律 `PUT /__admin/mappings/{id}` 原地更新（WireMock 3.13 实证支持）——TB-18 的 del/add 空档竞态同除；④ 守护只登记"新 POST"、不复活被脚本摘除的对象，"远端删除"模拟语义保持成立；⑤ compose down/up 后 `m2_probe_sync_republish_all` 按状态文件恢复（E2E-30 三形态已挂接）；⑥ 守护启动按 metadata 清扫上轮残留映射；⑦ journal 无响应体/无条目 id 的实情（195 实证）→ 去重键用 `loggedDate:external_id`，remote_id 一律回查 DB（产品自记的事实源）。
- **环境处置**：195 停 publisher 断环路 → TRUNCATE 全部 15 张业务表（保留 `flyway_schema_history`，证据已在 smoke-evidence 归档）→ 重建镜像起栈，90s 观察 `repair_request=0` 无复燃。
- **预防措施**：stub 替身"创建类"端点必须与探针端点状态联动（创建即可见），否则 drift/repair 类产品逻辑在 stub 世界必然失真；远端 id 号段纪律仍有效（显式 override 场景）。
- **压力点留档**（当期不改产品，G2/M4 评估）：修复链无全局节流——真实现网若出现"探针持续不见"的病态（权限半开/平台异常），AUTO 修复链会按巡检周期持续重建。建议 M4 评估"单资源修复频控 + 全局修复预算"。
- **关联**：TB-13、INC-44（前轮根）、E2E-32A/E2E-33、I26。

### INC-46 —— 已修复待回归（M2 第四轮回流 TB-14，崩溃恢复认领路径无 PUBLICATION_OUTCOME_UNKNOWN 留痕，产品小缺口）

- **现象**：E2E-29 FAIL——写达远端、CONFIRM 前 SIGKILL → 恢复扫描 IN_FLIGHT→RECONCILING 正确认领（探针认领/远端恰一/零重复创建全过），但 `PUBLICATION_OUTCOME_UNKNOWN` 事件 0（期望 1）。
- **根因**：`PublicationStore.java:76` javadoc 早已承诺"→RECONCILING（响应丢失，EX-03）+ PUBLICATION_OUTCOME_UNKNOWN 事件"，但扫描器路径（`OutboxRecoveryScanner.sweepExpiredInFlight` → `toReconciling`）从未落事件——活写路径（`FencedPublicationExecutor.markReconciling`）有、崩溃恢复路径没有，审计语义不对称。崩溃恢复与响应丢失同属"结果未知"（EX-03），javadoc 承诺未在扫描器落地。
- **解决方案**：`toReconciling` 增加 `ExecutionEvent` 参数（接口 + PG + Fake + Crashy 四处），转换命中时同事务落事件（`detail=inflight_lease_expired`）；`OutboxRecoveryScannerTest` 路径①补事件断言。
- **关联**：TB-14、E2E-29、EX-03。

### INC-47 —— 已修复待回归（M2 第四轮回流 TB-15，恢复扫描 UNKNOWN 退避的 Retry-After 被 120s 缺省底线无条件压制，产品缺陷）

- **现象**：E2E-32-B FAIL——探针吃 429+Retry-After=30 后 `reconcile_after - 观察时刻 = 118s`（期望 ≤+70s）；同参数写路径 34s PASS，互证缺陷只在恢复扫描路径。
- **根因**：`settleReconcile` UNKNOWN 分支**无条件** `max(exponential, now+120s)`——`RetryBackoff` 本身尊重 HonorRetryAfter（:31-32），被外层 max 抹平。I23 口径 = "显式头听头的（clamp 15min），缺省底线只兜无头指令"。
- **解决方案**：floor 条件化——verdict 携 `HonorRetryAfter` 时直接用 exponential，否则才 max 兜底；`OutboxRecoveryScannerTest` 新增两案钉死（Retry-After=30 不被压制 / 无头 429 仍受 120s 兜底），本机 10/10 绿。
- **关联**：TB-15、E2E-32-B、I23、EX-19。

### INC-48 —— 已修复待回归（M2 第四轮回流 TB-16/17/18，测试注入面三处修正，非产品缺陷）

- **TB-16（E2E-34 注入形态错误）**：原注入 list-check 端点级 404 撞上 M1 既定裁决——LIST 探针 404 归瞬时 UNKNOWN 退避（sha 消失/瞬断/权限皆可能，方向安全零误修），不进 sanity/权限告警路径；所谓"自动复归"实为资源从未离开 PRESENT。修正：改注"对象摘除（探针 200 空列表 → 窗口穷尽 NOT_FOUND）+ sanity 读 404"——权限告警路径（F-3）的正确触发形态；恢复段补探针对象回填后再做人工复位断言。产品 LIST 404 语义复验正确，不动。"LIST 404 是否也接 sanity 消歧"记压力点留 G2。
- **TB-17（E2E-31 注入竞态）**：原时序"3s 轮询等铸单 → pause"存在数秒窗口，repair 命令在 pause 生效前被 claim 并 POST（fence 断言本身全过，产品无缺陷证据）。修正：0.5s 密轮询等铸单、命中即冻结 publisher、显式检测命令是否抢跑，抢跑则换 PR 重试（≤3 轮）——竞态是概率事件，检测+重试比放宽断言诚实。
- **TB-18（BT-M2-03 换装竞态）**：`m2_review_present_set` 的 del/add 空档让探针撞静态空响应 → 伪 NOT_FOUND → 伪 MISSING + 游离 MANUAL 单（episode 主语义全过，产品无缺陷证据）。由 probe-sync 的 PUT 原地换装一并根除（INC-45③）。"episode 活跃期是否不重复判 MISSING"的产品防御性评估记压力点留 G2。
- **关联**：TB-16/17/18、E2E-31/E2E-34、BT-M2-03、F-3、EX-28。

### INC-49 —— 已修复（流程踩坑：主会话 tar 同步误带 M3 测试文件污染 195 构建；暴露"M2 产品代码从未提交 git"）

- **现象**：主会话同步 TB-14/15 修复到 195 时 tar 了整个 `publisher-app/src`——把本地工作区的 M3 适配测试文件（ItModelClient/ItHarness/EX06/EX07 引用 control 侧 M3 新类 `ModelGatewayPort`/`ModelRoute`/`ModelCallContext`）一并带入；195 上 control-app 是 M2-era，publisher testCompile 炸 20+ 处 cannot find symbol。
- **根因**：**M2 产品代码全部未提交 git**（HEAD=08035c2 为 M1-era），"195 的 M2-era 状态"只是工作区快照、无 VCS 锚点；主会话按"publisher 与 M3 无关"的直觉整目录同步，未料到 M3 工作碰过 4 个 publisher IT 文件。
- **解决方案**：195 重建改 `-Dmaven.test.skip=true`（部署栈只需 main jar，测试源码不编译；测试代码正确性由本机 `mvn clean verify` 全量把关——已 BUILD SUCCESS）。jar 内新代码实证（class 含 TB-14 新字符串 `inflight_lease_expired`）。
- **预防措施**：**M2 代码应尽快提交 git**（待用户指示）——无 VCS 锚点的跨机同步永远有此类风险；向 195 同步部署源码时若本地工作区混入未发布里程碑改动，必须按文件清单精确同步或先提交。
- **关联**：TB-14/15 部署、M3 工序 3 收尾中（本机未提交）。

### INC-50 —— 已修复待回归（M2 第五轮回流 TB-19，INC-49 次生面收尾：4 个 publisher IT 文件 M3 引用手工回退 M2-era）

- **现象**：第五轮阶段 A，195 上 reactor `clean verify` 的 publisher testCompile 炸——`ItModelClient/ItHarness/EX06ModelFailureIT/EX07T2RollbackReplayIT` 引用 control 侧 M3 新类（`ModelCallContext/ModelGatewayPort/ModelRoute/ModelRouteIdentity/RoutedModelResult`），195 的 control-app 是 M2-era 只有 7 个 `domain/ai` 类；publisher 139 单测+70 IT 无法编译（shared 101、control 301+52 全绿不受影响）。
- **根因**：INC-49 已记——主会话 tar 整目录同步把本地 M3-era 测试适配带入 195；M2 产品代码从未提交 git（HEAD=b4266bc 为 M1-era），M2-era 的这 4 个文件无 VCS 锚点，只能手工回退。
- **解决方案**：在临时目录（本地 M3-era 工作区不动）把 4 个文件回退到 M2-era API——ItModelClient 退回实现 `ModelClient`（取 git HEAD 版本，ModelClient/ModelRequest/ModelResult 在 M2 未变）；ItHarness 恢复 `ReviewAgentLoop(modelClient, new ModelBudgetGuard(), …)` 4 参 + `ReviewStepExecutor` 末参回 `String modelIdentity="it/mock-model/v1"`；EX06 回 `ModelTimeoutException("模型超时", null)`；EX07 的 `ReviewOutcome` 去掉 M3 第 9 参（195 M2-era 为 8 参，末位 `modelResponse`）。按精确文件清单 scp 到 195；195 上 `mvn test-compile -pl publisher-app -am` **EXIT=0 编译通过**（2026-09-01）。部署镜像无需重建（测试源码不进 jar，195 构建本就走 `-Dmaven.test.skip=true`）。
- **预防措施**：同 INC-49——M2 代码尽快提交 git（待用户指示）；跨机同步一律精确文件清单。
- **关联**：TB-19、INC-49。

### INC-51 —— 已修复待回归（M2 第五轮回流 TB-20，E2E-31 测试装备缺陷：docker pause 冻死 T14 token 口致换届迟到 4 分钟；fence 无缺陷）

- **现象**：E2E-31（repair 执行中 push 新 commit → epoch fence + EXPIRED）——INC-48 冻结修复后无抢跑（命令铸于换届前、publisher 即冻），但 unpause 后 repair 命令未被 fence 拦截反而 CONFIRMED（POST 写旧 head_sha），`stub 零写 实际=[1]`、`REPAIR_EXPIRED 事件=[0]`。
- **主会话取证裁定**（回码 + 195 DB 取证，**翻转执行方"游标序 fence"定性**）：① `PublicationGate:68-69` 第三参 `cursor.publicationEpoch()` 是 `pr_subject` 行 `FOR UPDATE` 现值（`PostgresPublicationStore:120-127`），不是"已解决游标处世代"——fence 比对的就是 subject 现世代，语义与 v2.2 §3/I22 一致；② 真实时间线：sync webhook `e31-sync` 10:34:48 入 inbox，处理连败 3 次（`last_error={"kind":"dispatch_failed","message":"UncheckedIOException: 只读 token 窄接口调用失败"}`），10:38:49 才 PROCESSED → epoch bump（新命令 seq 4/5 铸于 10:38:51）比 repair CONFIRMED（10:37:49）**晚 62 秒**——fence 判定时 subject 现世代确为 1，ALLOW 合法，产品零缺陷；③ 根因=**T14 架构事实**：控制面处理换届必须先经 publisher 的 CredentialBroker 只读 token 窄接口取 token，而 `docker pause` 把该口一起冻结 → 换届只能 RETRY_WAIT 到 unpause 之后；④ 脚本换届等待行的 `|| true` 吞掉 180s 超时，是假绿通道。
- **解决方案**（`deploy/e2e-m2.sh` E2E-31 重写，**行锁代 pause**）：pause 仅覆盖"铸单→行锁落地"短窗口（保证命令 PENDING 无竞态）→ 后台 psql 持 repair 命令行 `FOR UPDATE`（publisher claim 走 `SKIP LOCKED` 必跳过、T3-A `lockCommand` 必阻塞、sweep `FOR UPDATE OF o` 必阻塞——三条路径全被封死且 publisher 进程保持存活）→ unpause 恢复 T14 token 口 → 发 synchronize webhook，换届等待改**硬失败**（超时=用例无效，不再吞）→ 换届落定后 `pg_terminate_backend` 精确放锁 → fence（T3-A）或 sweep（兜底路径③）确定性 SUPERSEDED → projector 收敛 EXPIRED。全程无概率竞态（原"检测+换 PR 重试"降级为 pause 窗口的防御性兜底）。已同步 195，`bash -n` 通过。
- **设计观察留档**（不改产品）：T14 单连接点意味着 publisher 停机期间控制面无法完成换届（inbox 退避自愈，本次实证 62s 窗口）；单人系统可接受，若未来多实例/高可用诉求出现再评估控制面 token 短缓存。
- **关联**：TB-20、TB-17（INC-48 前轮）、E2E-31、I22、T14（评审修正 #6）。

### INC-52 —— 已修复待回归（M2 第六轮回流 TB-21/TB-22，probe-sync 常驻化 + 全链路超时加固；测试基础设施，产品零改动）

- **现象**：TB-21=栈在脚本防护窗外重启 → stub 运行时探针映射（admin 注册，内存态）全失 → ~690 历史资源逐个判 MISSING → drift-repair 长尾风暴（651→754 单）污染 C 阶段全局计数断言（E2E-28 铁证/E2E-33 高度一致）；TB-22=干净基线 C 复跑时 E2E-33 案内 check-run 无限重建循环自燃，probe-sync 案内"运行中"却未拦截。
- **根因**（195 取证实锤两层）：① **脚本生命周期守护的结构性缺口**——probe-sync 由 m2 脚本 fork 的子进程充当，trap 退出即停，且 `m2_cleanup` 还主动摘除全部探针映射；脚本外任何栈运行窗口 stub 即回到"重建对象不可见"的纯无状态态。② **案内冻结**——守护最后一轮扫描写文件止于 12:45:35 UTC（.scan-ck.json mtime 实证），恰在 E2E-33 修复循环第一个 POST（12:45:44）前 9 秒；循环内所有 curl/psql 调用**无超时**，风暴负载尖峰上单轮卡死即永久失能（`|| true` 只兜命令失败，不兜阻塞）。TB-22 ⑥猜想(a)"journal 体量截断"被证伪：.scan-ck.json 完整可解析、仅含 journal 当时真实内容（1 条）。
- **解决方案**（三方向裁定=**(a) 常驻化**；(b) stub 映射落盘单独不解决新建对象注册与案内冻结，(c) 产品感知 stub 违反架构纪律，均驳回）：
  1. 新增 `deploy/probe-sync-daemon.sh`（start/stop/status/ensure/run）：nohup 常驻、生命周期独立于测试脚本；每轮写 `heartbeat`；每轮探测已知 mapid 存活，stub 重启（404）即按状态文件全量重发布；启动时清扫本机制历史运行时映射 + republish + rv 状态文件 7 天退役 + seen.txt 超 5000 行截尾（游标连续性保留，防守护重启复活已摘除对象）。
  2. `m2-lib.sh`：状态目录稳定化为 `deploy/probe-sync-state/`（env `M2_PROBE_SYNC_DIR` 可覆盖；不再随 M2_EVIDENCE 一轮一清）；`m2_ps_init` 不再截断 seen.txt（游标归守护私有）；`m2_probe_sync_start` 改 ensure（心跳<15s+pid 活则复用，否则就地拉起）；`m2_probe_sync_stop` 改 no-op；`m2_cleanup` 不再摘探针映射（映射=stub 世界现状，与 DB 资源行同寿命）；**全外部调用加超时**（映射增删改/换装 curl `--max-time 10`，journal find `--max-time 15`，`m2_psql` 外层 `timeout 20`）；ck 可见数组封顶 200 条防跨轮累积。
  3. `.gitignore` 加 `deploy/probe-sync-state/`；`deploy/README.md` 机制说明同步。
- **预防措施**：常驻守护 + 心跳 + 全链路超时是测试替身边界机制的三件套，后续任何栈级守护一律照此；断言类教训见 INC-53。
- **关联**：TB-21、TB-22、TB-13（INC-45 前轮）、TB-18（INC-48 前轮）、INC-44。

### INC-53 —— 已修复待回归（M2 第六轮回流 TB-23，E2E-32-A 断言缺陷：全局 journal 计数不过滤本案命令 id；产品零缺陷）

- **现象**：E2E-32-A「429+Retry-After 退避窗口内 POST 恰 1」实测 2（t+0 与 t+3013ms），且同窗 next_attempt_at=+34s 断言 PASS——调度面/网面看似背离，低频 1/3。
- **主会话取证裁定**（证据目录 `e2e-20260901-202506` stub-checks.json + probe-sync seen.txt 铁证）：两条 POST 的 **external_id 不同源、head_sha 不同**（097c6e7a…/eeee… 与 0e6a6026…/deadbeef…），且在 seen.txt 中成对出现两次、间隔恰 30 秒（12:26:28.8/12:26:31.8 与 12:26:58.8/12:27:01.8）——第一条是**邻案 E2E-31 收尾的 synchronize（NEW_SHA=eeee…）换届评审管线**落入本案窗口的写，第二条才是本案 CREATE_CHECK；+30s 的第二对正是两条命令各自 429+RA:30 的**合法重试**。产品退避链全程正确（子代理代码推演亦证伪同一行早重发：CLAIM 尊重 next_attempt_at、prepare 双栅栏、无定时器旁路）。低频 1/3 = 邻案管线排空与本案断言窗口的相对时序。
- **解决方案**（`deploy/e2e-m2.sh` E2E-32-A）：窗口计数断言改按本案 CREATE_CHECK 的 operation_id 过滤（`m2_pr_op` 取号 + jq `contains` 计数），与 32-B/32-C 既有按 id 过滤的写法对齐。`bash -n` 通过。
- **预防措施**：stub journal 是全局命名空间——凡"POST 恰 N 次"类断言一律按本案 external_id/operation_id 过滤（TB-21 ⑦ E2E-28/33 同类教训：同合成 sha 下 head_sha 维度不可区分）；前后案管线异步排空的"迟到写"是用例编排固有噪声。
- **关联**：TB-23、TB-21 ⑦（同族断言缺陷）、E2E-32-A、I23。

### INC-54 —— 已修复待回归（TB-23 排查附带发现：CLAIM_SQL 缺租约条件——claim 家族唯一不完整栅栏；产品小改）

- **现象**（代码审查发现，无线上事故）：`PostgresPublicationStore.CLAIM_SQL` 的 ready CTE 不含 `lease_until` 条件——claim 只挂租约不改 state（仍 PENDING），事务提交后行锁释放，SKIP LOCKED 不再遮挡；第二 claimer 线程/实例可把同一 PENDING 命令再次领走（lease_epoch+1）。
- **根因**：claim SQL 的可用性条件漏了租约维度；当前 compose 单 publisher 实例 + claimer 单虚拟线程 + CAS 防重入，实际只造成 StaleLease 空转，但它是 claim 家族唯一的栅栏缺口，多实例即成双发通道。
- **解决方案**：CLAIM_SQL ready CTE 补 `AND (lease_until IS NULL OR lease_until < now())`——过期租约仍可回收（崩溃恢复路径不变）；已核对全部 T3-A 决策分支（DEFER/RECORD_GAP 走 releaseLease、markRetryWait/终态清租约、PROCEED 转 IN_FLIGHT），不存在"PENDING+活租约需被立即重领"的合法路径。新增 IT 回归 `OutboxClaimLeaseFenceIT`（活租约挡第二认领者 → 拨过期后可回收；红绿语义=修复前第二认领断言必红）。本机 publisher surefire 143/0 绿（IT 本机无 docker 跳过，真跑归 195）。
- **预防措施**：fake（FakePublicationStore）单线程语义不建模租约门——已留档，多实例诉求出现时须补 fake/PG 一致性（RM2-04 教训）；claim 家族 SQL 变更须过"租约/退避/状态"三维自查。
- **关联**：TB-23（附带发现）、I6/I7/I8、CT-21。

### INC-55 —— 已修复待回归（M2 第七轮回流 TB-25，新 CONFIRMED 资源首查零宽限：CONFIRM+0.38s 自然 tick × 探针登记 ~1s 延迟的竞态；产品小改）

- **现象**：BT-M2-03 断言「内容漂移只告警、零 repair 单」实测 1 张（REVIEW 资源 MANUAL PENDING；另 CHECK_RUN 资源 AUTO 单已 probe-first 零写自愈 REPAIRED）。同日 4 次中招（PR#22206/23139/31119/31271）。
- **主会话取证裁定**：与执行方判断一致——**测试基建竞态残余，非产品缺陷**（产品对探针空输入全链反应正确，effectively-once 未破坏）。根因：资源行创建时 `next_check_at` 吃列默认值 `now()`（V3 迁移），CONFIRMED 即立即可扫，首个自然 tick（CONFIRM 后 0.38s）早于 probe-sync 守护的 1s 轮询登记（TB-13/21/22 同族第五例）。修法三候选中 (a) 脚本确认/(b) 守护提速**都不能确定性压窗**（自然 tick 由产品调度，脚本/守护抢不过）；**(c) 产品侧首查宽限**是唯一能根除整族的修法，且生产语义同样成立（刚确认创建成功的对象 0.38s 后立即重探漂移纯属浪费）。
- **解决方案**（publisher，代码侧——不改 V1~V4 既有迁移，V5 编号已被 M3 占用）：`PostgresPublicationStore` 三处 `INSERT INTO publication_resource`（insertResource 正常+对账找回共用 / confirmRepairReplacement / reconcileConfirmRepairReplacement）显式 `next_check_at = now() + make_interval(secs => :graceSecs)`；新旋钮 `publisher.drift.first-check-grace-seconds`（默认 10s，yml 占位 `DRIFT_FIRST_CHECK_GRACE_SECONDS`），装配点 `PublisherWiringConfig.publicationStore`。IT 线束 ItHarness 刻意 `Duration.ZERO` 保持既有"确认即可扫"用例语义（Ex27/28 等大量 IT 的隐性依赖），CT24 同步补参。新增回归 `FirstCheckGraceIT`（1h 宽限 sabotage store：宽限窗内 runOnce 零处理+last_checked_at 仍 NULL；next_check_at 拨过去后正常扫描重排——force tick 兼容性实证）。javadoc 同步（DriftReconciler/PublicationStore）。
- **预防措施**：时间类初值语义必须显式落代码并配旋钮，不吃 DB 列默认值隐式语义；"基建提速压竞态窗"类修法先做确定性论证（能不能抢赢自然调度），抢不赢就修产品侧初值。195 侧测试文件须用 M2-era 基线打增量（ItHarness/CT24 工作区版本是 M3-era，直接盖会重蹈 INC-49/50）——本次已按 delta 移植。
- **关联**：TB-25、TB-13/21/22（同族）、I23、EX-28、FirstCheckGraceIT。

### INC-56 —— 已修复待回归（M2 TB-24，全 stub 模型模式双层基建缺陷：h2c 升级竞态 + 模型映射缺 Content-Type；基建侧修复零产品码）

- **现象**：全 stub 模式下五条悬置用例（DP-17/E2E-26/27/30/BT-M2-01）首开全灭：control 模型调用 `POST http://github-stub:8080/v1/chat/completions` 全部 `IOException: RST_STREAM` → review_run FAILED → checkpoint 不落 → 断言级联烧毁。
- **根因（两层）**：① **h2c 传输竞态**——M2-era 模型客户端 SpringAiModelClient → Spring AI 1.0.0 RestClient → JDK HttpClient 默认 HTTP/2 优先，对明文 stub 发 h2c 升级（journal 实证 UA `Java-http-client/21.0.12` + `Upgrade: h2c` + **chunked body**）；Jetty 不支持带请求体的 h2c 升级（上游 jetty.project#11588，wiremock#2461），chunked 形态踩进升级竞态回 RST_STREAM/EOF。带 Content-Length 的 GitHub stub 调用六轮一直绿正因为升级被拒后优雅回落 1.1。195 修前探针实证：chunked 探针 3 次中 2 次 EOF。② **模型映射缺 Content-Type**（原卡未记录的第二层）——WireMock 3.13.1 `jsonBody` 不自动补 `Content-Type: application/json`，传输修通后 Spring AI 报 no suitable HttpMessageConverter（application/octet-stream）。该模式六轮从未真跑，故第二层从未暴露。
- **解决方案**（全基建侧）：`deploy/docker-compose.yml` stub 启动命令加 `--disable-http2-plain`（关闭明文 HTTP/2，客户端优雅回落 1.1——没有任何客户端真需要与 stub 讲 HTTP/2）；`deploy/wiremock/mappings/stub.json` 模型映射 + `deploy/m2-lib.sh` `m2_model_delay_on` jq 负载两处补 `Content-Type: application/json`（grep 全 deploy/ 确认无第三条模型映射路径）。不选产品侧强制 HTTP/1.1（M3 已重写该客户端=死代码）；不选反代/独立实例（新组件违反 ADR-020 且无必要）。
- **验证**：195 探针修后 5/5 `HTTP_1_1 200`，h2c prior-knowledge 被拒、升级优雅回落；BT-M2-01 全 stub 模式单条实证 5/0（证据 `smoke-evidence/bt-20260902-111033`）；control 日志 rerun 窗口零 RST_STREAM。195 停在全 stub 模式备剩余四条续跑（回混合：恢复 `.env.mixed.bak-tb24` + force-recreate control-app）。
- **预防措施**："从未真跑过的模式"必须假设还有第二层缺陷（HX-01 先探针再整体跑）；stub 响应映射一律显式声明 Content-Type；journal 取证要看全请求头形态（Content-Length vs chunked 决定 h2c 升级命运）。
- **关联**：TB-24、DP-17/E2E-26/27/30/BT-M2-01、F-24（WireMock 保真度边界）。

### INC-57 —— 已关闭（M2 第八轮 TB-26，E2E-30-C 断言时序缺陷：在自己 down/up 之后查内存态 journal；产品零缺陷。第九轮执行方复验 E2E-30 12/0 全绿，两窗断言 =1/=0 双过）

- **现象**：E2E-30-C 断言「repair 远端写恰 1 次（恢复不重复写）」实测 0——断言在第 387 行查 stub journal，而第 377 行 `docker compose down` 已把 stub 内存态 journal 清空，崩溃前那次 POST 的 journal 记录不复存在。
- **根因**：断言设计时序缺陷——证据采集点在证据销毁点之后。产品恢复语义全对（零重复写、probe-first 认领、attempt=1、新 PRESENT 行链回、remote_id 正确，DB 侧证据链完整）。
- **解决方案**（`deploy/e2e-m2.sh` E2E-30-C）：拆栈前先取证——`m2_journal_find` 快照崩溃前 journal 到 `stub-checks-pre.json`；断言拆成两条确定性断言：「崩溃前 repair 远端写恰 1 次」（pre=1）+「恢复后 repair 远端零重复写」（post=0）。恰好一次的总量由 pre+post 两窗闭合证明，不再依赖单一日志面存活。
- **预防措施**：凡用例自编排含 `down/up`/重启栈的动作，journal/内存态证据一律**先取证后拆栈**；跨重启的"恰好一次"断言改用"重启前快照 + 重启后增量"两段闭合，或改断 DB/事件面（持久证据）。
- **关联**：TB-26、E2E-30-C、TB-13（stub 内存态同族教训：凡依赖 stub 内存态的断言都要先问它活多久）。

### INC-58 —— 已修复（M3 工序 4 部署验证发现：CT22V4UpgradeIT 硬编码断言 flyway 版本 =4，V5 落地后必败；M2-era IT 的 M3 适配遗漏，本机无 docker 盲区未暴露）

- **现象**：M3 代码首次 195 真跑全量 verify（2026-09-02），control IT 52 条中 CT22V4UpgradeIT 唯一败：`expected "4" but was "5"`。
- **根因**：M2 编码 CT-22 时把"全链升级后的 flyway 版本"硬编码为 4（两处断言 + 坏迁移注入用 V5__bad.sql 占位）；M3 新增 V5 正式迁移后，migrate 到最新=5、坏迁移占位的 V5 版本号撞正式迁移。M3 编码期的测试适配遗漏了这条 M2-era IT（本机无 docker，IT 从未本机跑过——INC-27 同族盲区）。
- **解决方案**：两处版本断言 4→5（注释改为"当前最新版"，javadoc 注明随迁移目录演进）；坏迁移注入改 V6__bad.sql（版本号必须未被占用）。
- **预防措施**：版本号/阶段号硬编码断言在新增迁移时必炸——部署门侧 DP-01/DP-19 已用迁移目录动态最大版本（TB-06 修复的既有正确姿势），IT 侧本次跟进；后续新增 V6+ 时全库 grep 既有 IT 的版本字面量。M3 类跨版本适配清单应含"M2-era IT 逐条过一遍版本/构造签名假设"。
- **关联**：CT-22、DP-19、INC-27（本机 docker 盲区）、TB-06（动态版本门禁先例）。

### INC-59 —— 已修复（M3 工序 4 部署验证发现：EX06ModelFailureIT 预算用例仍走 M0 语义（线束内 ModelBudgetGuard 兜底），M3 预算闸移至真 Gateway 后 mock 线束不再触发；IT 适配遗漏）

- **现象**：同上轮 195 verify，publisher IT 中 EX06ModelFailureIT.budgetExceededFailsStepAndLedgersEvent 败：`expected FAILED but was REVIEW_COMPLETE`——入队 completion=9000 超预算的 ModelResult，无人拒绝，评审照常完成。
- **根因**：M0/M2 的逐次预算校验在线束注入的 ModelBudgetGuard（ReviewAgentLoop 构造参数）；M3 把预算闸收进真 ModelGateway（ModelStepBudgetGuard，单测覆盖），ReviewAgentLoop 只认 ModelGatewayPort——IT 线束的 ItModelClient/MockModelGateway 不经过预算闸，EX06 测试 1 的"超预算用量→FAILED"前提不再成立。M3 编码期适配了 EX06 的超时半段（ModelCallFailedException 承载）但漏了预算半段。
- **解决方案**：EX06 测试 1 改按接口契约入队 `ModelBudgetExceededException`（ModelGatewayPort javadoc 明示的预算拒绝通道）——断言语义不变（WorkItemWorker→Step FAILED/MODEL_BUDGET_EXCEEDED/BUDGET_EXCEEDED 事件/零 outbox），预算判定本身归 ModelGateway/ModelStepBudgetGuard 单测。清理随之失效的 ModelResult/TokenUsage import。
- **预防措施**：删除一个"构造注入的守卫组件"时，必须全量盘点它的间接行为面（哪些测试靠它拦）；IT 适配清单按"异常类型×注入点"矩阵过，不只按编译错误过。
- **关联**：EX-06、M3 方案 §4.4（预算闸落点）、INC-58（同批 M3 适配遗漏）。

### INC-60 —— 已修复待回归（M3 工序 4 部署验证发现：markUnknownOlderThan 直接绑定 Instant，pgjdbc 07006 拒推类型 → Recovery 每分钟静默失败、超龄 STARTED 永不标 UNKNOWN；零真 PG 覆盖盲区）

- **现象**：195 起栈（V5 首上）后 control 日志每分钟一条 `账本 Recovery 扫描失败（下周期重试）: BadSqlGrammarException`；catch 块只记异常类名不记 message，PG 服务端零错误日志（语句根本没发到库）。
- **根因**：`PostgresModelCallLedgerRepository.markUnknownOlderThan` 用 `jdbc.update(sql, threshold)` 直接绑 `java.time.Instant`——pgjdbc 全版本不支持 Instant 类型推导（`Can't infer the SQL type to use for an instance of java.time.Instant`，SQLState 07006，Spring 误归类 BadSqlGrammarException）。该方法此前只有单测假仓储覆盖，无任何真 PG IT——M3 编码期"新仓储方法必须配真 PG 用例"的执行漏洞（CT 矩阵漏列）。195 探针（同驱动 jar + 同角色 + 同语句 + setObject(Instant)）1 次复现；手动 psql 同语义 UPDATE 正常，坐实是客户端类型绑定问题而非授权/DDL。
- **解决方案**：绑定改 `Timestamp.from(threshold)`（项目既有惯例，INC-60 注释留痕）。补真 PG 回归 `PostgresModelCallLedgerRepositoryTest`：超龄 STARTED→UNKNOWN+fresh 不动+终态不可改写+幂等重扫，insertStarted→completeTerminalSuccess 主写路径走真实 control_app 列级授权+V5 CHECK。连带修 `PostgresITBase` 清场清单漏 V5 表（ALL_TABLES + model_call_ledger，16 张，javadoc 同步）——不清场会让工序 5 的账本断言吃到 IT 残留。
- **预防措施**：① 新增仓储方法一律配真 PG 组件测试，"单测假仓储全绿"不构成持久层证据；② 时间参数绑定只用项目惯例 `Timestamp.from`，禁用 Instant 直绑（架构测试可加静态扫描：src/main 中 `jdbc.update(...Instant` 形态拒入）；③ catch 告警日志必须带 `e.getMessage()`（本次诊断被"只记类名"拖慢一轮，异常消息零成本且常常一句话定位）。
- **关联**：M3 方案 §4.6（Recovery 语义）、DP-22（账本冒烟依赖 Recovery 收敛超龄 STARTED）、INC-27/58（本机无 docker 盲区同族）。

### INC-61 —— 已修复（M3 工序 4 全 stub 窗口接线缺口：APP_MODEL_GATEWAY_TOTALDEADLINEMS 只进 .env 不进 compose 环境块，旋钮根本到不了容器；连带暴露 perCall ≤ totalDeadline 第三环）

- **现象**：全 stub 窗口（`.env.allstub.bak-r8`：lease=60 + TOTALDEADLINEMS=30000 已配对）切窗后 control 仍被 F-22 拒启；修好 compose 透传后又被 `perCallTimeout 不得大于 gatewayTotalDeadline` 拒启（per-call 默认 120s > 窗口 deadline 30s）。
- **根因**：两层。① compose control-app 环境块只透传了 M2 旋钮 `APP_WORKER_MAXLEASESECONDS`，M3 新旋钮 `APP_MODEL_GATEWAY_TOTALDEADLINEMS` 在 .env 里声明了但 compose 从不引用——`.env` 不是容器环境的隐式通道，未声明的键根本进不了容器（工序 4 之前全 stub 窗口从未真跑过 M3 代码，INC-56"未真跑模式必有第二层缺陷"同族）。② M3 启动校验不等式链是三环：lease > deadline + 10s 余量（F-22）∧ perCall ≤ deadline（ModelGatewayParams 构造）∧ recovery-after ≥ 2×perCall——只配平第一环不够。
- **解决方案**：compose 补两行透传（`APP_MODEL_GATEWAY_TOTALDEADLINEMS:-300000`、`APP_MODEL_PERCALLTIMEOUTMS:-120000`，默认值=代码默认，注释写明不等式链与合法三元组）；195 `.env.allstub.bak-r8` 补 `APP_MODEL_PERCALLTIMEOUTMS=20000`——演练窗合法三元组定型为 **lease=60 / deadline=30000 / percall=20000**（recovery-after 默认 240 ≥ 2×20 自动满足）。DP-28 随之重写为模式感知：混合模式断言 .env 零声明+渲染值=代码默认；演练窗断言三元组精确成对；CIRCUIT/LEDGER/MAXCALL 三个从未接线的旋钮保持三面零出现断言。
- **预防措施**：① 新增配置旋钮时必须"链路三段"同时落——yml 占位、compose 透传、部署门禁的渲染值断言，缺一即"旋钮是摆设"；② 不等式链有几个环就要在部署文档写几个环的合法组合，只写一个环的组合必在下一环炸；③ 门禁断言值为"名称出现次数=0"类时，一旦该旋钮被正式接线就必须同步改断言语义（残留检查→值检查）。
- **关联**：INC-56（未真跑模式同族）、F-22、DP-28、M3 方案 §4.9。

### INC-62 —— 已修复（G2 核心集真跑发现：E2E-48 双断言缺陷——OPEN_REJECT 时间锚查错列名静默返空 + 探针统计窗口把烧闸请求圈进探针集）

- **现象**：E2E-48 首跑 FAIL=1「无 OPEN_REJECT 事件时间锚」，但同案 REJ=149 事件计数断言 PASS（事件明明存在）；修列名后复跑又 FAIL「探针间隔过密 932ms<55s」。
- **根因**：两层。① TOPEN 查询写 `min(ee.created_at)`，`execution_event` 表无此列（正确列 `occurred_at`）——psql 报错被 `|| true` 链路吞掉，静默返空。② 探针统计窗口写 `loggedDate >= TOPEN-2000`，把熔断打开**之前**的 3 次烧闸请求圈进"探针"集；932ms 实为烧闸请求互间隔。真实时间线（journal 实证）：3 次烧闸（3.3s 内）→ OPEN → 91s 后 1 个 HALF_OPEN 探针，完全符合"冷却 60s、每周期恰一发"。
- **解决方案**：TOPEN 改 `min(ee.occurred_at)`；探针窗口严格从 TOPEN 起算（`loggedDate >= $t0`），新增断言"首个探针距开闸 ≥55s"（冷却期内零提前放行）。
- **预防措施**：① 取证 SQL 列名必须对 `\d <table>` 核实，"查询返空"与"事件不存在"必须可区分（psql 错误不得静默吞）；② 时间窗断言先画出真实事件时间线再定窗口边界，容差方向要想清楚往哪边偏。
- **关联**：M3 方案 §11 E2E-48（G2-H3）、e2e-m3.sh `e2e_48`。

### INC-63 —— 已修复（G2 核心集真跑发现：E2E-49 被跨案污染——E2E-48 的 50 个 FAILED 演练 PR 未收尾，PrStateReconciler 按设计重燃，30 次真实模型调用冲进后案 journal 窗口）

- **现象**：E2E-49「恢复后模型调用恰 1 次」实测 31；故障窗内零请求、账本恰 1 行 SUCCEEDED 均 PASS，唯独 journal 全局计数爆表。
- **根因**：E2E-48 的 50 个演练 PR 全部 FAILED 终态但在 stub 侧仍 OPEN；`PrStateReconciler`（M2 既有自愈设计，`reconciler:pr-state:` 合成 intake）扫描到"OPEN 且无成功评审"即重燃——30 个重燃 Run 各打 1 次真实模型调用（09:20:07~11 密集爆发，账本 `started_at` 逐行实证），落在 E2E-49 的 journal 计数窗口内。另查明 195 上另有 12 个 M2 时代同类残留主体，为全套件级污染源。
- **解决方案**：① `e2e_48`/`e2e_60` 收尾段把本案演练 PR 主体就地 CLOSED（`update pr_subject set state='CLOSED'`），掐断重燃源——污染源治理优先于放松断言；② 195 存量 62 个残留 OPEN 主体一次性 CLOSED 清场；③ E2E-49 复跑 6/0 全绿（「恰 1 次」=1 实证）。
- **预防措施**：① 任何"故意制造 FAILED Run"的用例，收尾必须回答"这些 OPEN 主体下一轮 reconciler 扫描会怎样"——FAILED≠环境静默；② journal 全局计数类断言对跨案并发天然脆弱，用例设计要么收窄计数口径、要么保证前案零残留；③ 排障先查账本 `trigger_key` 前缀（`reconciler:`/`repair:`/delivery_id 三族一眼分流）。
- **关联**：`PrStateReconciler`（control/application）、e2e-m3.sh `e2e_48`/`e2e_60` 收尾段。

### INC-64 —— 已修复（G2 核心集真跑发现：E2E-51 缺熔断器进程内存态隔离 + E2E-60 断言误解 §4.4「耗尽即 Fail」语义）

- **现象**：dual-distinct 批 E2E-51「崩溃前主侧恰 3 次」实测 0、账本实测 1 行（期望 4）；E2E-60「step_attempt 恰 3 行」实测 1。
- **根因**：两案各一层。① E2E-51 与 E2E-42 同容器连跑仅隔 ~10s：E2E-42 主侧三连烧 500 已把主路由熔断器（进程内存态，冷却 60s）留在 OPEN，E2E-51 主侧调用被 OPEN_REJECT 快败、零触网直切备——fallback 本身按设计工作，但用例前提"主侧烧 3 次再切备"被前案状态短路。② E2E-60 双路由同挂 500 时，物理调用预算 6（主备共享，§4.4）在 attempt1 内 3 主+3 备烧满 → 耗尽即 Fail，Step 一 attempt 直接 FAILED；原断言"恰 3 attempt"误解了方案语义（attempt 预算是进程崩溃级兜底，本场景不行使；对比 E2E-48：OPEN_REJECT 快败不耗物理预算才走满 3 attempt）。产品行为与方案一致，错的是断言。
- **解决方案**：① `e2e_51`/`e2e_60` 开头重启 control 归零熔断器（同 E2E-48 既有口径），隔离前案内存态；② E2E-60 断言改"step_attempt 恰 1 行 + 账本恰 6 行"，等待文案同步修正；③ 复跑双绿（各 12/0）。
- **预防措施**：① 凡断言依赖熔断器初始态的用例，开头必须重启归零或显式等待冷却——进程内存态是跨案隐藏耦合面；② 写断言前先对方案语义条目（§4.4 预算共享/耗尽语义），"预期行为"不得凭直觉写。
- **关联**：M3 方案 §4.4（I35 预算 Step 级共享）、§4.10（熔断器内存态）、e2e-m3.sh `e2e_51`/`e2e_60`。
