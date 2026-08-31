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
