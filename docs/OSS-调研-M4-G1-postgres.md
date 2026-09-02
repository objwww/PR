# OSS 调研：M4-G1 PostgreSQL 断言逐条核实

- 调研日期：2025-09-02（以抓取官方页面当日为准）
- 一手来源：PostgreSQL 16 官方文档（https://www.postgresql.org/docs/16/），全部为本地 curl 抓取的原文页面核对
- 背景：`sandbox_job`（state: PENDING/LEASED/SUCCEEDED/FAILED/REJECTED/CANCELLED）多 worker 以 `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)` 领取作业，全局要求同一时刻至多一个作业在跑；另有 `tool_call` 与 `sandbox_job` 互相引用。

判定图例：**成立** / **部分成立** / **不成立**

---

## 1. SKIP LOCKED 与全局并发

**断言摘要**：SKIP LOCKED 只保证两个并发 claimer 不抢到同一行，不能阻止它们分别领取不同的 PENDING 行——即 SKIP LOCKED 无法实现"全局 LEASED ≤ 1"。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/sql-select.html（The Locking Clause）
- https://www.postgresql.org/docs/16/explicit-locking.html（13.3.2 Row-Level Locks）

**关键原文**：

> "With `SKIP LOCKED`, any selected rows that cannot be immediately locked are skipped. Skipping locked rows provides an inconsistent view of the data, so this is not suitable for general purpose work, but can be used to avoid lock contention with multiple consumers accessing a queue-like table."
> 中文释义：使用 SKIP LOCKED 时，无法立即加锁的被选中行会被**跳过**。跳过加锁行会提供不一致的数据视图，不适合通用场景，但可用于多个消费者访问"队列式"表时避免锁争用。

> "Row-level locks do not affect data querying; they block only *writers and lockers* to the same row."
> 中文释义：行级锁不影响数据查询，只会阻塞对**同一行**的写者和加锁者。

要点：SKIP LOCKED 的全部语义就是"跳过已被别人锁住的行"，锁的粒度是**行**。两个 claimer 各自扫描时，若锁定了不同行则互不阻塞、各自成功领取。官方文档明确它的设计目标是"多消费者消费队列"（即天然允许多个消费者同时取到不同任务），而不是全局互斥。要实现"全局 LEASED ≤ 1"必须引入额外的全局串行机制（见 2/3/4 条）。

**对本项目设计的含义**：现有 claim SQL 无需推翻，但 SKIP LOCKED 本身不构成并发 1 的保证；它是"领取原子性"的手段，必须叠加方案 A/B/C 之一。

---

## 2. 单行信号量表（容量槽）

**断言摘要**：单行容量槽表（如 `sandbox_capacity_slot`，`PRIMARY KEY CHECK(slot_no=1)`），claim 在同一事务里先 `SELECT ... FOR UPDATE` 锁住该槽行再领作业，串行化所有 claimer。核实可行性并指出代价。

**判定：成立**（机制由官方行锁语义直接保证；"槽表"是通行应用模式，官方文档无专门一节，属于官方语义的标准应用）

**一手来源**：
- https://www.postgresql.org/docs/16/explicit-locking.html（13.3.1 / 13.3.2）

**关键原文**：

> "`FOR UPDATE` causes the rows retrieved by the `SELECT` statement to be locked as though for update. This prevents them from being locked, modified or deleted by other transactions until the current transaction ends."
> 中文释义：FOR UPDATE 把选中的行按"将要更新"的方式锁住，在当前事务结束前阻止其他事务对这些行加锁、修改或删除。

> "Two transactions can never hold conflicting locks on the same row." / "Row-level locks are released at transaction end or during savepoint rollback, just like table-level locks."
> 中文释义：两个事务绝不可能在同一行上同时持有冲突锁；行级锁在事务结束（或回滚到保存点）时释放。

> "Once acquired, a lock is normally held until the end of the transaction."
> 中文释义：锁一旦获得，通常持有到事务结束。

**代价（官方文档可佐证的点）**：

> "So long as no deadlock situation is detected, a transaction seeking either a table-level or row-level lock will wait indefinitely for conflicting locks to be released. This means it is a bad idea for applications to hold transactions open for long periods of time."
> 中文释义：只要不发生死锁，等待冲突锁释放的事务会**无限期等待**——因此应用不宜长时间持有打开的事务。

即：槽行是全局串行点，所有 claimer 在该行上排队；锁持有时间 = 事务长度，持锁期间所有其他 claimer 阻塞（吞吐上限 = 1/claim 事务时长）；worker 崩溃时事务回滚、行锁自动释放，槽位自动归还（崩溃安全性好）。另外行锁会使被锁行产生磁盘写（"locking a row might cause a disk write, e.g., `SELECT FOR UPDATE` modifies selected rows to mark them locked"），热点单行在高频 claim 下有额外写放大。

**对本项目设计的含义**：可行且崩溃安全。注意 `PRIMARY KEY CHECK(slot_no=1)` 更地道的写法是 `slot_no smallint PRIMARY KEY CHECK (slot_no = 1)` 并插入唯一一行；claim 事务必须"先锁槽行、再 SKIP LOCKED 领作业"，顺序固定可避免死锁（官方建议"以一致顺序获取多个对象上的锁"）。

---

## 3. 部分唯一索引（常量键）

**断言摘要**：`CREATE UNIQUE INDEX ... ON sandbox_job ((1)) WHERE state='LEASED'` 使数据库层面至多一行 LEASED。核实 Postgres 支持常量表达式部分唯一索引及其效果；分析与"LEASED 后还要区分 RUNNING/执行中"状态模型的兼容性。

**判定：成立**（含两点注意：表达式索引键需为 immutable 表达式，常量是合法退化形式；谓词必须覆盖"全部在跑"状态）

**一手来源**：
- https://www.postgresql.org/docs/16/indexes-partial.html（11.8 Partial Indexes，Example 11.3）
- https://www.postgresql.org/docs/16/indexes-expressional.html（11.7 Indexes on Expressions）

**关键原文**：

> "A third possible use for partial indexes does not require the index to be used in queries at all. The idea here is to create a unique index over a subset of a table, as in Example 11.3. This enforces uniqueness among the rows that satisfy the index predicate, without constraining those that do not."
> 中文释义：部分索引的第三种用途根本不需要查询用到它：在表的子集上建唯一索引，**只对满足谓词的行强制唯一**，不满足谓词的行不受约束。（Example 11.3 即"只允许每个 subject/target 组合有一条 successful 记录"的写法。）

> "An index column need not be just a column of the underlying table, but can be a function or scalar expression computed from one or more columns of the table. ... If we were to declare this index UNIQUE, ..."
> 中文释义：索引列不必是表的真实列，可以是函数或由列计算出的标量表达式；表达式索引可以声明为 UNIQUE。

机制说明：索引键 `(1)` 是常量表达式（不引用任何列），是表达式索引的合法退化形式；谓词 `WHERE state='LEASED'` 限定子集。效果：任意时刻满足 `state='LEASED'` 的行在索引中的键值全为同一个常量 1，唯一约束保证**至多一行**满足谓词；第二个 claimer 把某行置为 LEASED 的 UPDATE 会在提交前的唯一性检查处报 `duplicate key value` 错误而失败（且该 UPDATE 本身持有行锁，并发下会串行等待，最终仅一个成功）。

兼容性分析（断言要求注意的点）：该索引把"在跑名额"绑定在枚举值 `LEASED` 上。若未来状态机要拆分为 `LEASED`（已领取）+ `RUNNING`（执行中），谓词必须同步改为 `WHERE state IN ('LEASED','RUNNING')`，否则 RUNNING 期间的作业不受约束、名额会被绕过。本项目当前状态机（PENDING/LEASED/三终态+CANCELLED）中 LEASED 即"全部在途"，不存在该问题；但这是模式与状态机定义的**强耦合点**，须写进 schema 注释。另注意 explicit-locking 13.3.2 的细节：部分索引/表达式索引不被计入"决定 UPDATE 锁级别"的唯一索引集合，即对这种索引键的 UPDATE 不会额外升级为 FOR UPDATE 级行锁——对本用途无负面影响。

**对本项目设计的含义**：与现有 SKIP LOCKED claim 语句零冲突（claim 的 UPDATE 照常执行，冲突者吃到唯一冲突错误后重试/放弃即可）；不变量持久化在数据库层，与"崩溃后由 reaper 按 lease_until 回收再领"完全兼容（回收只是把 state 置回 PENDING，自然让出索引名额）。

---

## 4. advisory lock

**断言摘要**：`pg_advisory_xact_lock` / `pg_advisory_lock` 作全局互斥。核实事务级锁释放时机、会话级锁在连接断开时是否自动释放（崩溃安全性）、"先拿 advisory lock 再 claim"模式是否被官方认可。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/explicit-locking.html（13.3.5 Advisory Locks）
- https://www.postgresql.org/docs/16/functions-admin.html（9.27.10 Advisory Lock Functions）

**关键原文**：

> "PostgreSQL provides a means for creating locks that have application-defined meanings. These are called *advisory locks*, because the system does not enforce their use — it is up to the application to use them correctly. Advisory locks can be useful for locking strategies that are an awkward fit for the MVCC model. For example, a common use of advisory locks is to emulate pessimistic locking strategies ... While a flag stored in a table could be used for the same purpose, advisory locks are faster, avoid table bloat, and are automatically cleaned up by the server at the end of the session."
> 中文释义：Postgres 提供由应用定义语义的"劝告锁"，系统不强制使用，由应用保证正确性；常用于不适合 MVCC 模型的锁策略，典型用法就是模拟悲观锁。相比在表里存标志位，劝告锁更快、不产生表膨胀，且**会话结束时由服务器自动清理**。

> "Once acquired at session level, an advisory lock is held until explicitly released or the session ends. ... Transaction-level lock requests, on the other hand, behave more like regular lock requests: they are automatically released at the end of the transaction, and there is no explicit unlock operation."
> 中文释义：会话级劝告锁持有到显式释放或**会话结束**；事务级劝告锁则与常规锁一样，**事务结束时自动释放**，没有显式解锁操作。

> "pg_advisory_xact_lock ( key bigint ) → void — Obtains an exclusive transaction-level advisory lock, waiting if necessary."
> 中文释义：获取排他的事务级劝告锁，必要时等待。

逐点回应：
- **事务级释放时机**：事务结束（提交或回滚）自动释放，无显式 unlock——成立。
- **会话级崩溃安全性**：锁由服务器端锁管理器持有、绑定会话；连接断开（包括进程崩溃导致的 TCP 断连）即会话结束，锁被服务器自动清理——成立（比"表内标志位"更耐崩溃，官方原文明说 "automatically cleaned up by the server at the end of the session"）。
- **官方认可度**：官方明确其定位就是"application-defined meanings"的应用级互斥，并给出模拟悲观锁的典型用例——成立。

注意点（官方文档同样给出）：所有劝告锁占用共享内存锁表（受 `max_locks_per_transaction`、`max_connections` 限制）；含 `LIMIT` 的查询中直接调 `pg_advisory_lock(...)` 有因表达式求值顺序导致"意外多拿锁"的风险，官方示例明确标注 `-- danger!`，应先把 id 选出（子查询物化）再加锁。会话级锁不遵守事务语义（回滚不释放、需配对 unlock，且同会话可重入计数），连接池场景容易"挂锁"，**本项目应优先用 `pg_advisory_xact_lock`**。

**对本项目设计的含义**：`SELECT pg_advisory_xact_lock(<固定key>)` 作为 claim 事务第一条语句，即可获得与事务同生命周期的全局互斥，崩溃/回滚自动释放；配合 `pg_try_advisory_xact_lock` 可实现"抢不到就直接返回空"而非排队。可用 `pg_locks` 视图观测持有者。

---

## 5. 循环外键与 DEFERRABLE

**断言摘要**：两表互相 NOT NULL 引用在即时约束下首行无法插入；官方解法是 DEFERRABLE（INITIALLY DEFERRED，提交时校验）。核实语义与限制（唯一约束是否也可延迟、对唯一索引类型的要求），并给出"只留单向 FK"与"延迟约束"的取舍依据。

**判定：成立**（循环 FK 需 DEFERRABLE 解法是成立的；限制细节见下）

**一手来源**：
- https://www.postgresql.org/docs/16/sql-createtable.html（DEFERRABLE / INITIALLY DEFERRED / REFERENCES 小节）
- https://www.postgresql.org/docs/16/ddl-constraints.html（5.4 Foreign Keys）

**关键原文**：

> "This controls whether the constraint can be deferred. A constraint that is not deferrable will be checked immediately after every command. Checking of constraints that are deferrable can be postponed until the end of the transaction (using the `SET CONSTRAINTS` command). NOT DEFERRABLE is the default. Currently, only UNIQUE, PRIMARY KEY, EXCLUDE, and REFERENCES (foreign key) constraints accept this clause. NOT NULL and CHECK constraints are not deferrable. Note that deferrable constraints cannot be used as conflict arbiters in an INSERT statement that includes an ON CONFLICT clause."
> 中文释义：不可延迟的约束在每条命令后立即检查；可延迟约束可推迟到事务结束检查。默认 NOT DEFERRABLE。**目前只有 UNIQUE、PRIMARY KEY、EXCLUDE、REFERENCES（外键）接受该子句**；NOT NULL 和 CHECK 不可延迟。可延迟约束不能作为 ON CONFLICT 的冲突仲裁对象。

> "If the constraint is INITIALLY IMMEDIATE, it is checked after each statement. This is the default. If the constraint is INITIALLY DEFERRED, it is checked only at the end of the transaction."
> 中文释义：INITIALLY IMMEDIATE 每条语句后检查（默认）；INITIALLY DEFERRED 只在事务结束时检查。

> （FK 引用目标的要求）"the refcolumn list must refer to the columns of a **non-deferrable** unique or primary key constraint or be the columns of a **non-partial unique index**."
> 中文释义：外键的引用列必须指向**不可延迟**的唯一/主键约束的列，或**非部分**唯一索引的列。

> "When a UNIQUE or PRIMARY KEY constraint is not deferrable, PostgreSQL checks for uniqueness immediately whenever a row is inserted or modified. ... To obtain standard-compliant behavior, declare the constraint as DEFERRABLE but not deferred (i.e., INITIALLY IMMEDIATE). Be aware that this can be significantly slower than immediate uniqueness checking."
> 中文释义：唯一约束也可声明为 DEFERRABLE；但延迟的唯一性检查可能比即时检查**显著更慢**。

逐点回应：
- 双向 NOT NULL 即时 FK：任一首行插入都要求其引用行已存在，构成死结——成立（由 FK 即时校验语义直接推出）。
- 官方解法：把至少一侧 FK 声明为 `DEFERRABLE INITIALLY DEFERRED`（或事务内 `SET CONSTRAINTS ALL DEFERRED`），同一事务内先插两行、提交时校验——成立。
- 唯一约束可延迟：可以（UNIQUE/PK 接受 DEFERRABLE）；限制：延迟唯一检查显著更慢；不能用作 ON CONFLICT 仲裁；**其他表的 FK 不能引用可延迟的唯一约束**（引用目标必须 non-deferrable / 非部分唯一索引）——这是设计时最容易踩的坑。

**取舍依据**（对本项目 tool_call ↔ sandbox_job）：
- **只留单向 FK**（推荐，若业务允许）：如 tool_call.job_id → sandbox_job.id 保留 NOT NULL FK，反向引用 sandbox_job.current_tool_call_id 改为普通列（不加 FK 或加可空 FK + 应用层维护）。代价：反向引用无数据库层完整性保证；收益：插入顺序天然有解（先 job 后 tool_call）、无延迟检查开销、无 ON CONFLICT/被引用限制。
- **双向 DEFERRABLE**：数据库层双向完整，但所有插入必须包在事务里且成对写入；约束在提交时才报错（错误定位更晚）；受上述"被引用目标必须 non-deferrable"等限制，且每端 FK 都有触发器开销。

---

## 6. CHECK 不能管状态迁移

**断言摘要**：行级 CHECK 只能约束单行最终形态，无法禁止非法状态迁移（如 SUCCEEDED→RUNNING）；正确做法是仓储层限定条件 UPDATE（`UPDATE ... WHERE state=:expected AND lease_epoch=:epoch`，0 行=冲突）。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/ddl-constraints.html（5.5 Check Constraints）

**关键原文**：

> "PostgreSQL does not support CHECK constraints that reference table data other than the new or updated row being checked. While a CHECK constraint that violates this rule may appear to work in simple tests, it cannot guarantee that the database will not reach a state in which the constraint condition is false (due to subsequent changes of the other row(s) involved)."
> 中文释义：CHECK 约束不支持引用除"被检查的新行/更新行"以外的表数据；即使简单测试中看似有效，也无法保证其他行后续变化不破坏约束条件。

> "PostgreSQL assumes that CHECK constraints' conditions are immutable, that is, they will always give the same result for the same input row. This assumption is what justifies examining CHECK constraints **only when rows are inserted or updated, and not at other times**."
> 中文释义：Postgres 假设 CHECK 条件是不可变的（同一输入行永远同样结果），因此**只在插入或更新行时**检查 CHECK，其他时候不检查。

要点：CHECK 只能看到**更新后**这一行的值，拿不到旧值（OLD），因此"禁止 SUCCEEDED→LEASED"这种依赖迁移前后两个状态的规则在 CHECK 中根本无法表达；且 CHECK 是"新行满足谓词"的快照校验，不是持续性的跨行/跨时间保证。合法的迁移约束通道只有：(a) 条件 UPDATE（`WHERE state=:expected AND lease_epoch=:epoch`，影响行数 0 即冲突——乐观并发控制），或 (b) BEFORE UPDATE 触发器比较 OLD/NEW（见第 9 条）。

**对本项目设计的含义**：状态机合法性（允许的有向边集合）放在仓储层条件 UPDATE（与 lease_epoch 乐观锁天然合并成一条 SQL）；CHECK 只用于"终态列组合合法"这类单行不变量（如 `state='LEASED'` 则 `lease_until IS NOT NULL`）。

---

## 7. 列级 UPDATE 权限

**断言摘要**：`GRANT UPDATE (col1,col2) ON ...` 支持列级授权，且整行权限使列级限制无效。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/sql-grant.html

**关键原文**：

> （语法）`GRANT { { SELECT | INSERT | UPDATE | REFERENCES } ( column_name [, ...] ) [, ...] | ALL [ PRIVILEGES ] ( column_name [, ...] ) } ON [ TABLE ] table_name [, ...] TO role_specification [, ...]`
> 中文释义：GRANT 的第二种变体即对指定列授予 SELECT/INSERT/UPDATE/REFERENCES。

> "A user may perform SELECT, INSERT, etc. on a column if they hold that privilege for either the specific column or its whole table. Granting the privilege at the table level and then revoking it for one column will not do what one might wish: the table-level grant is unaffected by a column-level operation."
> 中文释义：只要用户对**某列单独**或**整表**二者之一持有权限，即可对该列执行相应操作。先授整表权限、再对单列 REVOKE 达不到预期效果——表级授权不受列级操作影响。

要点：列级权限是**叠加（OR）语义**，不是收窄语义。要让"某角色只能 UPDATE 某几列"成立，前提是该角色没有整表 UPDATE；否则列级限制形同虚设。

**对本项目设计的含义**：若打算用列级 GRANT 限制 worker 角色只能改 state/lease_* 列，必须确保该角色从未获得 sandbox_job 的整表 UPDATE（包括经角色继承/ PUBLIC 获得）。

---

## 8. JSONB 内容校验

**断言摘要**：CHECK 约束里可对 jsonb 列做 `jsonb_typeof(...)='array'`、元素类型、长度等校验，这些函数在 CHECK 中可用（immutable）。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/functions-json.html（9.16.1 Processing Functions / 9.16.3 SQL/JSON Query Functions）
- https://www.postgresql.org/docs/16/ddl-constraints.html（CHECK 的 immutable 假设，见第 6 条引文）

**关键原文**：

> "`jsonb_typeof ( jsonb ) → text` — Returns the type of the top-level JSON value as a text string. Possible types are `object`, `array`, `string`, `number`, `boolean`, and `null`."
> 中文释义：返回顶层 JSON 值的类型（object/array/string/number/boolean/null）。

> "`jsonb_array_length ( jsonb ) → integer` — Returns the number of elements in the top-level JSON array."
> 中文释义：返回顶层 JSON 数组的元素个数。

可用性论证：官方明确 CHECK 只要求表达式对同一输入行结果稳定（immutable 假设）；`jsonb_typeof`、`jsonb_array_length`、`jsonb_path_exists` 等均为内置 immutable 函数（可在系统目录 `pg_proc` 的 `provolatile='i'` 查证），符合 CHECK 使用条件。示例：

```sql
CHECK (
  jsonb_typeof(payload) = 'array'
  AND jsonb_array_length(payload) > 0
  AND NOT jsonb_path_exists(payload, '$[*] ? (@.type() != "object")')
)
```

注意边界：`jsonb_typeof` 对 SQL NULL 返回 NULL，CHECK 视 NULL 为通过（官方：约束在表达式值为 true 或 null 时满足），非空要求需另加 `NOT NULL`；元素级深度校验建议用 `jsonb_path_exists` 的 jsonpath 谓词（也是 immutable），不要在 CHECK 里写子查询（CHECK 不允许子查询/引用他行，见第 6 条）。

**对本项目设计的含义**：sandbox_job/tool_call 的 jsonb 载荷可在 DDL 层兜底"顶层类型+非空+元素类型"这类单行不变量，与第 6 条结论一致：只约束单行形态，不管迁移。

---

## 9. 触发器守不可变列

**断言摘要**：可用 BEFORE UPDATE 触发器比较 OLD/NEW 禁止指定列变化。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/trigger-definition.html（39.1 Overview of Trigger Behavior）
- https://www.postgresql.org/docs/16/plpgsql-trigger.html（43.10 Trigger Procedures）

**关键原文**：

> "Row-level BEFORE triggers fire immediately before a particular row is operated on..."
> 中文释义：行级 BEFORE 触发器在某行被操作的前一刻触发。

> "A row-level trigger fired before an operation has the following choices: It can return NULL to skip the operation for the current row. ... For row-level INSERT and UPDATE triggers only, the returned row becomes the row that will be inserted or will replace the row being updated."
> 中文释义：行级 BEFORE 触发器可返回 NULL 跳过本行操作；对 INSERT/UPDATE，返回的行将替代被插入/更新的行。

> "`NEW record` — new database row for INSERT/UPDATE operations in row-level triggers. ... `OLD record` — old database row for UPDATE/DELETE operations in row-level triggers."
> 中文释义：PL/pgSQL 触发器中自动提供 NEW（新行）与 OLD（旧行）两个 record 变量。

> （官方示例 emp_stamp 中）`IF ... THEN RAISE EXCEPTION '...'; END IF;` —— BEFORE INSERT OR UPDATE 触发器内用 `RAISE EXCEPTION` 中止非法修改。
> 中文释义：官方示例即在触发器中对非法字段抛异常拒绝写入。

做法即：`CREATE TRIGGER ... BEFORE UPDATE ON t FOR EACH ROW` + 函数内 `IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION ...; END IF; RETURN NEW;`——比较 OLD/NEW 并抛异常，即可实现"INSERT 后不可变"。这是官方文档语义直接支持的通行做法。

**对本项目设计的含义**：对 id、lease_epoch 之外真正不可变的列（如创建时确定的业务键）可用触发器兜底；但它与第 6 条的条件 UPDATE 是互补关系——迁移合法性用条件 UPDATE 更高效（0 行即冲突，无需进入触发器），不可变列用触发器更适合防"绕过仓储层的直连 SQL"。

---

## 10. grant 计费的并发安全

**断言摘要**："used_bytes 不超上限"类计数，并发安全做法（单事务 `UPDATE ... SET used_bytes=used_bytes+:n WHERE used_bytes+:n<=max RETURNING`，或 SELECT FOR UPDATE）是否官方认可；核实 UPDATE 自带行锁的语义。

**判定：成立**

**一手来源**：
- https://www.postgresql.org/docs/16/transaction-iso.html（13.2.1 Read Committed Isolation Level）
- https://www.postgresql.org/docs/16/explicit-locking.html（13.3.2 Row-Level Locks）

**关键原文**：

> "`UPDATE`, `DELETE`, `SELECT FOR UPDATE`, and `SELECT FOR SHARE` commands behave the same as `SELECT` in terms of searching for target rows ... However, such a target row might have already been updated (or deleted or locked) by another concurrent transaction by the time it is found. In this case, the would-be updater will wait for the first updating transaction to commit or roll back ... If the first updater commits, the second updater will ignore the row if the first updater deleted it, otherwise it will attempt to apply its operation to the updated version of the row. **The search condition of the command (the `WHERE` clause) is re-evaluated to see if the updated version of the row still matches the search condition.** If so, the second updater proceeds with its operation using the updated version of the row."
> 中文释义（关键）：Read Committed 下，并发 UPDATE 同一行时后者**等待**前者提交/回滚；前者提交后，后者在**已更新的行版本上重新评估 WHERE 条件**，满足才继续执行。

> "The commands `UPDATE`, `DELETE`, `INSERT`, and `MERGE` acquire this lock mode [`ROW EXCLUSIVE`] on the target table..."（表级）以及行级 "The `FOR UPDATE` lock mode is also acquired by any `DELETE` on a row, and also by an `UPDATE` that modifies the values of certain columns" / "FOR NO KEY UPDATE ... is also acquired by any `UPDATE` that does not acquire a FOR UPDATE lock."
> 中文释义：UPDATE 语句本身就会对目标行取行级排他锁（按是否改唯一键列分别为 FOR UPDATE / FOR NO KEY UPDATE 级别），无需先 SELECT FOR UPDATE。

结论：单条原子 UPDATE（`SET used_bytes = used_bytes + :n WHERE used_bytes + :n <= max`，再看 RETURNING/影响行数）在默认 Read Committed 下即并发安全——行锁使两个并发扣减串行，且**后到的 UPDATE 会在最新行版本上重算 WHERE**，超限者匹配 0 行自然失败。这正是官方隔离级别文档描述并保证的行为，属于官方认可的计数器模式；先 SELECT FOR UPDATE 再 UPDATE 是等价的显式两阶段写法，效果相同但多一次往返。（注意不要在 Read Committed 下用"先普通 SELECT 读值、应用层判断、再 UPDATE 绝对值"的写法——那是丢失更新。）

**对本项目设计的含义**：grant 扣费直接写一条带谓词的原子 UPDATE 即可，不必引入额外锁；判断"是否成功扣减"用 RETURNING 是否返回行（或 affected rows）。

---

## 总结：全局并发 1 的三方案对比与推荐

| 维度 | A 单行槽表 SELECT FOR UPDATE | B 部分唯一索引 `((1)) WHERE state='LEASED'` | C `pg_advisory_xact_lock` |
|---|---|---|---|
| 不变量位置 | 事务行锁（过程性） | 数据库持久约束（声明式） | 服务器锁管理器（过程性） |
| 崩溃回收兼容 | 好：崩溃回滚自动放锁，配合 lease_until reaper | 好：崩溃后行仍 LEASED，由 reaper 置回 PENDING 即释放名额 | 好：会话断开自动清锁，LEASED 行仍靠 reaper |
| 可观测持有者 | 间接（pg_locks + 槽行） | 最直接：`SELECT * FROM sandbox_job WHERE state='LEASED'` | 间接（pg_locks 视图，granted/objid） |
| 与 M2 SKIP LOCKED claim 风格一致性 | 高：同一事务内先 FOR UPDATE 槽行再走原 claim | 最高：claim SQL 不变，仅多处理唯一冲突错误 | 高：claim 前加一条 SELECT pg_advisory_xact_lock |
| 防御"绕过仓储层的直连 SQL" | 否（靠纪律） | 是（约束对一切写入生效） | 否（劝告锁，系统不强制） |
| 主要代价 | 全局串行点、持锁时间=事务时长、热点行写放大 | 第二个 claimer 以唯一冲突错误失败（需捕获重试）；与状态机枚举强耦合 | 连接池下事务级锁随连接复用无碍，但跨代码路径全靠自觉加锁 |

**推荐：方案 B（部分唯一索引）为主，方案 A 或 C 可选叠加。**

理由：B 把"全局 LEASED ≤ 1"固化为数据库层持久约束，任何路径的写入都无法绕过，崩溃后由既有 lease_until 回收流程自然释放名额，且当前持有者可直接 SQL 查询观测；它与 M2 既有 SKIP LOCKED claim 语句零冲突（仅需在仓储层把 unique_violation 翻译为"名额占用"冲突），风格最一致。A/C 只在"希望第二个 claimer 安静等待而非报错"时作为互补手段叠加。

---

### 十条判定一览

1. SKIP LOCKED 不能保证全局 LEASED ≤ 1 —— **成立**
2. 单行槽表 SELECT FOR UPDATE 可行（代价：串行点+持锁时间） —— **成立**
3. 常量表达式部分唯一索引可强制至多一行 LEASED —— **成立**（谓词须覆盖全部"在跑"状态）
4. advisory lock 作全局互斥，事务级随事务释放、会话级随断开释放 —— **成立**（建议用 xact 版）
5. 循环 FK 需 DEFERRABLE INITIALLY DEFERRED —— **成立**（注意被引用唯一约束须 non-deferrable、延迟唯一检查更慢）
6. CHECK 管不了状态迁移，用条件 UPDATE —— **成立**
7. 列级 GRANT 存在且整表权限使列级限制失效 —— **成立**
8. jsonb_typeof / jsonb_array_length 可用于 CHECK —— **成立**
9. BEFORE UPDATE 触发器比较 OLD/NEW 守不可变列 —— **成立**
10. 原子 UPDATE ... WHERE used+n<=max 并发安全（UPDATE 自带行锁、WHERE 在新行版本上重估） —— **成立**
