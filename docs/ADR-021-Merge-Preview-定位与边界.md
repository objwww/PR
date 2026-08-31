# ADR-021 Merge Preview：定位、身份模型与失效规则

> 状态：**设计输入 · 未冻结** ｜ 2026-08-31
> 来源：外部评审修正 + 当日官方文档核查（证据 F-4/F-5）
> 里程碑纪律：本文只冻结目标、身份模型、失效规则与资源纪律；**M1/M2 不实现任何部分**。
> 最早动工点：M5 之后先做确定性 pairwise preview（见 §五 排期）。

## 一、市场判断（收窄后，可经追问）

~~"现有工具只在单个 PR 上工作、没有跨 PR 合并预演"~~ ——该表述已不准确，废弃。

经核查的事实（F-4/F-5）：

- **队列类工具做组合验证，但在入队后**：GitHub Merge Queue 对 merge_group 累积组合跑
  required checks；Mergify 支持批量合并 + speculative checks + 失败二分拆批；Trunk 按
  impacted targets 建动态并行队列并预测性测试前序组合。三者共同点：**按队列顺序、
  只跑确定性 CI、对象是已入队的 PR**。
- **评审类工具做语义分析，但只逐 PR**：CodeRabbit（历史 PR learnings + 整仓上下文）、
  Greptile（全仓代码图 + 跨仓 context.repos）均无"构造多个在飞 PR 组合树做验证"的
  公开功能；Greptile T-REX 沙箱执行的粒度也是逐 PR。

## 二、定位（冻结措辞）

> **在 PR 入队前，主动选择可能互相影响的在飞 PR，构造可复现的组合树，
> 先做确定性验证，再对组合差异做语义评审，并把双边证据写回各 PR。**

创新点不是"第一次组合 PR"，而是五条可防守的差异：

1. 入队**前**主动发现（不等队列收敛）；
2. 不限于队列顺序（任意在飞 PR 两两组合）；
3. 确定性构建与语义分析**分层**（前者便宜且确定，后者贵且或然，各记各的账）；
4. 双 PR 证据链（结论写回 A 和 B 两侧，不是只报给合并队）；
5. 可恢复、可回放的组合 Run（复用本项目账本体系，见 §四）。

## 三、技术边界（评审四点修正，全部采纳）

### 3.1 候选发现：不能只用文件 Jaccard

文件交集为零的组合恰恰是高危组合（重命名方法 vs 新增调用者）：

```text
candidate(A,B) = fileOverlap ∨ sameBuildTarget ∨ dependencyImpact ∨ sharedSymbol
```

MVP 只实现"**相同文件 ∨ 相同 Maven module**"，调用/依赖图留待后续；每 PR 限 top-K。

### 3.2 合并顺序必须入账

`A→B` 与 `B→A` 的冲突行为不一定相同。组合输入至少记录：

```text
base_sha / head_sha_a / head_sha_b / merge_order / merged_tree_digest / build_profile_digest
```

MVP 固定稳定顺序（按 PR 编号升序），但 `merge_order` 必须写进账本——顺序是输入不是惯例。

### 3.3 身份模型：不复用 GitHub merge_group

GitHub `merge_group` 是平台创建的队列对象，生命周期/来源/失效条件与内部预演完全不同。
统一抽象、不同类型：

```text
ValidationTarget
  - PULL_REQUEST            （现有 Run 的对象）
  - GITHUB_MERGE_GROUP      （平台队列对象，被动观测）
  - INTERNAL_PAIR_PREVIEW   （本系统主动创建，自有 preview_id 与 generation）
```

### 3.4 双边发布非原子

两个 PR 的 Check 是两条独立远端写入，与本架构 Outbox 语义天然兼容：

- 每条命令各自 effectively-once（复用现有 Outbox/lease/reconcile）；
- 任一 head SHA 改变 → 整个 Preview 立即过期（复用 epoch fence 思路）；
- 一侧成功一侧失败 = 可恢复中间态，不是错误；
- 不承诺 GitHub 两侧原子可见。

## 四、与本架构的衔接（不新增承重机制）

组合 Run 复用 `parent_run_id / root_run_id / execution_scope` 既有生长点（冻结文档已预留）；
Preview 的"创建-验证-发布-过期"走既有 Step/Attempt/Outbox 骨架，不引入新中间件。

## 五、排期与资源纪律

1. M5 后先做 `git merge + compile/test` 的 **确定性** pairwise preview；
2. 确定性版本稳定命中合成案例后，才加 Agent 语义档；
3. 调度：**普通 PR Run 优先，Preview 仅在沙箱空闲时领取**；第一版不实现抢占；
4. 组合爆炸控制：top-K 候选 + 每 PR 每日 Preview 预算上限（与模型预算闸同族）。

*—— ADR-021 完。冻结需用户在对应里程碑 G1 确认。*
