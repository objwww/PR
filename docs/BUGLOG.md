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
