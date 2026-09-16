# 3.16 Prompt 工作台 — 测试截图文档

- 批次：前端产品化 · Wave 5（方案 v1 §3.16；定时自驱循环第 3 轮）
- 日期：2026-09-16
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道访问）
- 路由：`/prompt`（侧栏「质量 → Prompt 工作台」）
- 新后端：`GET /api/v1/prompt-workbench/assets`（版本列表+投产用量）、`GET /api/v1/prompt-workbench/active-plan`（当前激活包生效能力版本）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本页对齐 |
| --- | --- | --- |
| PromptLayer / 虾评 | Prompt 版本管理、历史可追溯、版本 diff | PROMPT 资产按创建时间倒序列表（46 版），任意两版本行级 diff 弹窗（红删/绿增/灰同） |
| Langfuse/Helicone 成本归因 | 每版本关联实际调用量与最近使用 | 投产状态徽章（投产中(近7天有调用)/已投产/未投产）+ 调用次数——来自 `rca_model_call.role_digest` 与 PROMPT 内容 `role_digest` 的真实匹配 |
| Bonree ONE 智能体发布 | 发布/暂存草稿双状态、发布即受控 | 版本不可变只读；"生效能力版本"由当前激活配置包（#151）直出，发布引导至版本中心受控激活（RELEASE 角色服务端裁定），本页不做直改 |
| Microsoft Agent 365 MCP evaluate | 评测入口与生产准备度联动 | 页头直链"前往评测中心发起对比实验"（评测中心版本×数据集对比实验复用） |

**只可多不可少**：业界清单（版本列表/正文/对比/发布状态/试跑/评测联动）全齐，且多出"投产状态=调用账本真匹配"（业界多用自报状态，我们用模型调用账本 role_digest 反推，更硬）。

## 二、截图清单

| # | 文件 | 内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 48 | 48-prompt工作台-首屏.png | 统计行（46/4/11/#151）+ 生效能力版本表（metrics@1/logs@1/change@1 及对位提示词）+ 版本列表（投产状态徽章） | `/api/v1/prompt-workbench/*` | 四项与 SQL 全对上 ✔ |
| 49 | 49-prompt工作台-版本正文.png | 选中 primary@1 版本正文全文 + variables_schema 标签 | `/api/release-assets/PROMPT/{digest}` | 正文与 release_asset.content 一致 ✔ |
| 50 | 50-prompt工作台-版本逐行对比.png | 版本 diff 弹窗：基准 1d5b2ad2（v2 策略）vs 当前 b3fa6124（v3 策略），行级红删绿增 | 双资产正文前端 LCS diff | 差异与两版本模板原文一致（v2→v3 取证顺序重构：新增 prometheus.rules 第一路） ✔ |
| 51 | 51-prompt工作台-试跑区.png | 试跑（Playground）：选事件→发起调查（当前生效配置）；发布/评测联动入口 | `/v1/incidents` + 既有重查路径 | — |

## 三、SQL 对账（2026-09-16 真机）

```sql
select count(*) from release_asset where asset_kind='PROMPT';                                 -- 46 = 面板
select count(distinct content->>'role') from release_asset
 where asset_kind='PROMPT' and content->>'role' is not null;                                  -- 4  = 面板
select count(distinct r.content->>'role_digest') from release_asset r
 where r.asset_kind='PROMPT'
   and r.content->>'role_digest' in (select distinct role_digest from rca_model_call);        -- 11 = 面板
select b.revision from config_bundle_active a
 join config_bundle b on b.bundle_digest=a.bundle_digest limit 1;                             -- 151 = 面板
```

## 四、验收（方案 §6 逐项）

1. 零错误数据：统计四项与 SQL 逐一对账一致；对位提示词版本由资产账本直出 ✔
2. 零裸枚举：投产状态以中文徽章渲染（投产中/已投产/未投产），无机器码上屏 ✔
3. 零可点必失败：正文按需懒加载；试跑未选事件时按钮禁用；重查被拒时展示后端中文原因 ✔
4. 空态即引导：无正文时说明"版本不可变、只读"；激活包无任务时明示"如实留空" ✔
5. 无新增迁移（纯读面）✔；权限沿 `/api/v1/**` OPERATOR 只读矩阵 ✔

## 五、试跑与发布的诚实边界（不造假声明）

- **试跑**：对指定事件发起一次真实调查（复用既有受控重查路径），使用**当前生效配置包**运行；"用指定历史版本试跑"需要构造临时配置包，涉及受控发布链路扩展，本批不做（页面明示）。
- **发布**：Prompt 版本经由配置包受控激活生效（版本中心既有 RELEASE 机器线命令面），本页提供引导链接，不重复建设直改入口——与"版本不可变"纪律一致。

## 六、遗留

- Playground 的"历史版本试跑"待临时包构造方案评审后开放。
- primary 角色 38 个历史版本缺少"策略名"结构化字段（策略版本号嵌在模板正文里），后续可在资产内容里加 `strategy_name` 键提升可检索性（需随下次资产登记批一起改，不动旧资产）。
