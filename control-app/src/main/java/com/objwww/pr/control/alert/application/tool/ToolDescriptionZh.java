package com.objwww.pr.control.alert.application.tool;

import java.util.Map;

/**
 * 工具注册面中文用途词典（BA-176 人读面）：工具名 → 一句话说明"这个工具干什么、
 * 查什么数据"。数据与各 Catalog/Agent 注册常量逐条对齐（DirectReadToolCatalog/
 * MetricsAgent/LogsAgent/ChangeAgent/MutationToolCatalog），Prompt 工作台工具
 * 注册面表格「用途」列取此。
 *
 * <p>BA-183 起每条兼负<b>模型调用契约面</b>（ContextAssembler 信封 tool_schemas
 * description 同源取用）：描述尾部带调用约束——必填参数/时间格式/窗幅上限，与
 * 各 executor 语义校验面（INVALID_ARGS 分支）逐字对齐。约束口径纪律：
 * <ul>
 *   <li>时间格式写"ISO-8601 或 epoch 秒"（四 executor BA-183 双收对齐 logs 族）；
 *   <li>窗幅上限/severity 等代码常量封闭值可以写（随 executor 改即同步）；
 *   <li>service allowlist <b>成员</b>是部署配置（BA-182 env 面），<b>不写进词典</b>
 *       ——只写"限部署白名单"，越界拒因经 V88 反馈环回喂模型，两处零漂移；
 *   <li>未命中一律回退工具名本身（绝不瞎编用途，同 RootCauseZhDictionary
 *       回退纪律）；新工具注册时同步补条目，词典只增不改。
 * </ul>
 */
public final class ToolDescriptionZh {

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            // 三兼容工具（MetricsAgent/LogsAgent/ChangeAgent）
            Map.entry("prometheus.query",
                    "用 PromQL 拉取冻结窗内的指标曲线——确认症状真实存在、幅度与起止点。"
                            + "必填 query/start/end/step：start/end 为 epoch 秒（窗幅 ≤3600 秒），"
                            + "step 形如 30s/1m（≤60s）"),
            Map.entry("logs.query",
                    "查冻结窗内的服务日志（WARN/ERROR 优先）——找第一条异常与错误模式。"
                            + "必填 since/until（ISO-8601 或 epoch 秒，窗幅 ≤900 秒），"
                            + "service 可选（限部署白名单）"),
            Map.entry("change.query",
                    "查冻结窗内的发布与配置变更记录——回答“告警前有没有人改过东西”。"
                            + "必填 since/until（ISO-8601 或 epoch 秒，窗幅 ≤900 秒），"
                            + "service 可选（缺省 control-app，限部署白名单）"),
            // EN-05 P0 直查工具族
            Map.entry("prometheus.instant",
                    "查某个 PromQL 表达式在指定时刻的瞬时值（曲线查询的对偶）。"
                            + "必填 query/time（time 为 epoch 秒）"),
            Map.entry("prometheus.metric_value",
                    "按指标名+标签直接查冻结窗内的数值，无需手写 PromQL。"
                            + "必填 metric/service/time（time 为 epoch 秒）"),
            Map.entry("prometheus.catalog",
                    "列出当前可用的指标名目录——回答“有哪些指标可查”。必填 service"),
            Map.entry("prometheus.label_values",
                    "查某个标签的全部取值（如 service 下有哪些服务名）。"
                            + "必填 label，match 可选"),
            Map.entry("prometheus.rules",
                    "查告警规则的 PromQL 表达式——先看告警怎么定义，再按表达式取证。"
                            + "必填 alertname"),
            Map.entry("logs.aggregate",
                    "日志聚合统计——按服务/级别统计冻结窗内日志量与错误占比。"
                            + "必填 since/until（ISO-8601 或 epoch 秒，窗幅 ≤900 秒），"
                            + "service/severity 可选（severity 限 ALL/ERROR/WARN/INFO）"),
            Map.entry("change.diff",
                    "对比两个发布/配置版本的差异——回答“这段时间具体改了什么”。"
                            + "必填 since/until（ISO-8601 或 epoch 秒，窗幅 ≤900 秒），"
                            + "service 可选（缺省 control-app，限部署白名单）"),
            Map.entry("docker.ps", "列出靶场容器运行状态——确认服务进程是否活着。"
                    + "无必填参数"),
            Map.entry("docker.inspect", "查单个容器详情——镜像、环境变量、重启次数。"
                    + "必填 container（容器名）"),
            Map.entry("alert.history",
                    "查历史告警事件台账——某告警何时触发/恢复、持续多久、历史频次。"
                            + "必填 since/until（ISO-8601 或 epoch 秒，窗幅 ≤72 小时）"
                            + " + alertname/fingerprint 至少一项"),
            // EN-07 RAG 工具族
            Map.entry("rca_history.search",
                    "搜历史 RCA 调查结论——类似故障以前是怎么定位根因的。"
                            + "必填 service（限部署白名单）+ since/until"
                            + "（ISO-8601 或 epoch 秒，窗幅 ≤30 天）"),
            Map.entry("runbook.catalog",
                    "检索运维手册目录——按症状找相关处置手册条目。match/tag 均可选"),
            Map.entry("runbook.fetch",
                    "取运维手册正文——读某条手册的具体处置步骤。"
                            + "必填 runbook_id（目录条目 id）"),
            // R7-X10 代码取证条件件
            Map.entry("code.search", "检索服务源码——按关键字找相关代码位置。"
                    + "必填 service+query，path_prefix 可选"),
            Map.entry("code.read", "读源码文件片段——核对可疑代码行的真实逻辑。"
                    + "必填 service+path，line_start/line_end 可选"),
            // BA-171 写类审批工具
            Map.entry("service.restart",
                    "重启指定服务（写类高危：调用即铸人工审批单，两人审批通过后才执行）。"
                            + "必填 service"),
            Map.entry("service.rollback",
                    "回滚指定服务到上一版本（写类高危，同走两人人工审批链）。"
                            + "必填 service"));

    private ToolDescriptionZh() {
    }

    /** 工具用途中文说明；未命中回退工具名本身（不瞎编） */
    public static String of(String toolName) {
        if (toolName == null) {
            return "";
        }
        return DESCRIPTIONS.getOrDefault(toolName, toolName);
    }
}
