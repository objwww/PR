# 告警分类 + 值班仿真机器人 + 列表工程迭代方案 v1

> 2026-09-11审查修订：具体审查、评测页面、Prompt/Skill/MCP版本演进与48例专项验收见[评测中心与能力版本演进详细方案](告警-评测中心与能力版本演进-审查及详细改造方案-v1.md)。本文件原位修正分类、仿真归属和进度语义；不代表已经实施。§〇截图依据须结合国内界面报告的复核更正使用。

> 2026-09-11。依据：用户验收反馈（告警无分类、值班群机器人不可交互、列表难看）+ 具名调研清单（见 §〇）。
> 本方案为设计与待实现规格，不代表已编码。编码门禁：本方案经用户批准后开工。

## 〇、调研依据（具名清单，先于设计——调研先行铁律）

**仓库内已有调研**（`research/alert-ux-research-202609/`，2026-09-11 完成）：
- 《README》四问调研（Grafana/PagerDuty/Robusta-HolmesGPT/BigPanda/incident.io/Langfuse/Datadog LLM Obs，24 张官方截图）；
- 《alert-web-改进建议》（代码走读 × 业界对照，进度条/自动刷新/报告卡差距清单）；
- 《日志与卡点可见性》《积压可见性与评测可靠性》（盲区清单 + P0 落点）；
- 《参考界面-国内告警系统》（夜莺 3 张实图 + 观测云事件中心 2 张实图 + SLS/蓝鲸入口）。

**本方案新增定向调研**（2026-09-11，针对分类与机器人两个具体设计点）：

| 设计点 | 具名来源 | 抄什么 / 不抄什么 |
|---|---|---|
| 告警分类体系 | [PagerDuty Incident Priority](https://support.pagerduty.com/main/docs/incident-priority)：先建统一 classification scheme 再谈优先级；[incident.io 告警疲劳治理](https://incident.io/blog/reducing-alert-fatigue-in-incident-management)：按 severity 分类 + 自动分组标注 | 抄"先定分类口径、再规则归类"；不抄 [PagerDuty Intelligent Alert Grouping](https://support.pagerduty.com/main/docs/intelligent-alert-grouping) 的 ML 分组（第一期规则可解释、可测试、可审计，ML 无审计链） |
| 分类置信度与人工修正 | [incident.io Catalog 路由](https://incident.io/blog/incident-io-vs-pagerduty-comparison)（按 service 归属路由）+ 夜莺规则分组思路 | 规则命中留依据（规则 ID + 置信度），人工修正覆盖规则并留审计——对应 PagerDuty"人工 merge 反馈"的位置，但我们用显式 override 而非喂 ML |
| 值班机器人交互 | [PagerDuty Slack App](https://support.pagerduty.com/main/docs/slack-user-guide)：值班在聊天里用 slash 命令/交互按钮完成 认领→升级→解决 全生命周期；[incident.io Slack-native](https://incident.io/blog/implementation-guide-slack-native-incident-management-platform-2026)：工作流发生在聊天命令里而非 Web UI；[Slack marketplace: incident.io](https://slack.com/marketplace/A01DEGPUHHC-incidentio)：从任意消息 declare incident | 抄命令集（认领/升级/静默/当班查询）与"聊天即操作面"形态；我们第一期是**站内仿真**（演练面），不接真 Slack/钉钉回调 |
| 仿真群外观与卡片 | [钉钉互动卡片回调文档](https://open-dingtalk.github.io/developerpedia/docs/explore/tutorials/stream/bot/go/card-callback)、[钉钉互动卡片 FAQ](https://dingtalk.apifox.cn/doc-3662821)：卡片按钮绑定回调事件 | 抄卡片即表单的外观语义；真回调链路是后期接真机器人时的事，本迭代只做站内仿真回执 |
| 列表工程（C1/C2） | 夜莺活跃告警（级别徽章/持续时长/列配置）、观测云事件中心（状态×级别×来源三连筛选 + 持续时长列）、PagerDuty 队列（创建时间列、最老优先）——均见仓库内调研实图 | 抄"已持续时长"列、进度可视、自动刷新；列配置/批量操作本期不做 |

---

## 一、告警分类（taxonomy v1 落地）

### 分类体系（7个领域类 + 平台类 + 未分类）

| 分类 | 口径 |
|---|---|
| 业务 BUSINESS | 订单失败率/SLO/交易损失等业务指标告警 |
| 应用 APPLICATION | 异常/延迟/状态机/进程（应用自身） |
| 依赖 DEPENDENCY | DB/MQ/缓存/下游 API |
| 基础设施 INFRA | 主机/容器/CPU/内存/磁盘 |
| 网络 NETWORK | 连接/DNS/TLS/丢包 |
| 数据 DATA | 数据质量/新鲜度/流水线 |
| 安全 SECURITY | 越权/泄露/策略违规 |
| 平台 PLATFORM | 控制面自身异常 |
| 未分类 UNCLASSIFIED | 尚无足够信息归类；不能默认视为平台异常 |

### 实现

- **分类器**：`IncidentClassifier`纯函数，受限匹配规则依据alertname及可信labels产生category、ruleId、规则版本和命中依据；severity独立，不单凭严重度推分类。多命中按冻结优先级/稳定ruleId裁决，正则限定复杂度和输入长度。未经校准的规则权重不叫概率置信度。规则走Git审查，首期不热更。
- **存储**：新Flyway迁移取下一个空闲版本；保存category/category_rule/category_rule_version与分类来源。人工override及reason/actor/revision单独保留，投影/历史回填不能覆盖override；撤销override是显式审计命令。既有category_confidence如已落库需标非概率或废弃读面，不悄悄换语义。
- **人工修正**：`POST /api/v1/incidents/{id}/category-override`，认证身份+幂等键+expectedRevision，审计和投影同事务提交。原始labels/alert_event不改写；修正优先于规则，冲突返回409。
- **读面**：incident 列表/详情投影带 category 三字段 + override 标记。
- **前端**：分类facet可收起，窄屏用筛选抽屉；计数明确当前时间/状态/服务过滤口径。行内分类徽章，详情显示规则版本与命中依据及带理由的修正入口，不展示未经校准的概率。
- **测试**：分类器单测（每类 ≥2 规则用例 + 未分类兜底 + override 优先）；投影写入 IT（真 PG）；override 审计 IT。

## 二、值班仿真机器人（演练面，可交互）

### 形态

值班 > 通知预览页升级为**可对话的仿真值班群**：底部加输入框，用户发消息，机器人（PR 告警中心）在同一会话流里回复。页面继续明确标"演练"——仿真不证明真实企微/钉钉送达。

### 实现

- **后端**：在control-app既有认证域新增`/api/sim/chat`端点组（POST发消息/GET历史），不放duty-adapter。后者当前无JDBC/security且承担独立外部故障通知，不能为聊天引入对195数据库的依赖。命令解析器纯函数可测：
  - `帮助`——列可用命令；
  - `当班`——读真值班快照回答当前当班人/通道链；
  - `告警`——读真库（只读）列出当前 FIRING 告警 Top N（名称/严重度/持续时长）；
  - `认领 <incidentId>` / `升级 <incidentId>` / `静默 <incidentId> <时长>`，也可从卡片选择目标；使用独立simulation状态，回执“模拟已认领/模拟已静默”，不修改真实Case/静默/通知状态。重名告警必须消歧，时长由服务端限界；
  - 任意其他文本——引导回 `帮助`。
- **机器人回复渲染**：复用 DutyNotificationRenderer 的卡片骨架，回复与通知同外观（企微/钉钉式样）。
- **持久化**：由control-app Flyway管理最小仿真消息/状态表，消息带session、认证用户、clientMessageId、处理状态、createdAt，唯一键防重发；会话历史按权限过滤，真告警只读投影遵守相同授权。不能仅有文本而没有命令幂等结果。
- **前端**：聊天气泡区分用户（右侧）/机器人（左侧）；发送后乐观上屏 + 失败回滚提示。
- **测试**：命令解析单测（每命令正/误用例）；端点测试；195 部署后截图取证（发问→机器人回复全程）。

## 三、并入的调研 P0 项（列表工程，对照夜莺/观测云）

- **C1 队列进度可见**：RCA调查队列可消费已投影done/total，称“当前计划已完成任务”，不当剩余时长或成功率。Eval列表须先补案例阶段/计数后端，不能照搬RCA字段。刷新复用visibilitychange暂停，并加queryKey/请求序号、去重和身份切换清理；不打断选中/输入。
- **C2 告警列表"已持续时长"列**（夜莺/观测云同款）：数据源 `episode_started_at` 已有，前端加列 + 排序；告警中心统计条加"未发起调查 N 条"卡（后端 summary 加一个聚合：FIRING 且 current_rca_run_id IS NULL）。
- **C3 告警行分类徽章**：见第一节，与 facet 一起落地。

## 四、明确不做（本次迭代范围外）

- token/预算、报告、inbox/卡点、实验对比和评审已被本轮用户要求纳入整体方案，按EV卡分批实施，不塞入本分类卡。费用必须先补RCA身份与调用账本；judge先校准，不替代程序规则和人工复核。
- 机器人不做真 LLM 应答（那是 R7 收口后的增强面，不在本迭代）。
- 不改 API 契约既有字段语义，只加字段/端点。

## 五、验收

- `mvn -pl control-app,duty-adapter -am test` 全绿（存量不变红）；
- 195 部署后截图：分类 facet + 徽章 + 修正操作、机器人一问一答、队列进度条、持续时长列；
- 证据存 `docs/测试证据/UI-分类与机器人-20260911/`。
