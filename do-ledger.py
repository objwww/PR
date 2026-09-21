# -*- coding: utf-8 -*-
# 落账双写：工作树追加我的行（保留对方 WIP 行）；index 构造 HEAD+我的行（我的提交自包含）
import subprocess

BUG = "docs/告警-BUGLOG.md"
PROG = "docs/告警-PROGRESS.md"

BUG_LINES = [
"| BA-133 | 已定位待修（195 真机定谳；run26 收官窗） | **logs.aggregate 映射自 EN-05 log_error_aggregate 但 Loki 查询无 error 过滤——数的是窗内全量日志行（任意级别）**，且空 vector 抛 NO_DATA 而非 count=0：历 run 的「聚合计数 72/100」实为 checkout 流量波动被当「错误突发」（run20/21 误读根因）；模型按「错误计数」语义引用它支撑判断时证据语义失真 | run25 聚合 count=4 而 claim 自述「计数为零」（payload 实证 [{\"count\":4}]）；LokiAggregateExecutor 查询原文 sum by(service_name)(count_over_time({service_name=…}[Ns])) 无 |= 过滤（Explore 定谳）；checkout 近 24h 36788 行零 error 行（detected_level=error 与 (?i)fail|error 双探针零匹配） | EN-05 工具映射面（log_error_aggregate→logs.aggregate）只搬了端点没搬过滤语义——名实错位让 prompt 配方「③错误计数」建立在假语义上 | 修复候选（待主会话裁定）：executor 域内加 error 过滤面（或 detected_level 标签选择器）；空 vector 改 count=0 诚实计数；DirectReadToolCatalog schema 描述同步钉语义 | 工具名即契约——映射面改名必须连语义一起搬；聚合工具的空结果=0 不是 NO_DATA | 路径一 SUMMARY §五；LokiAggregateExecutor.java；run25/run26 rca_evidence |",
"| BA-134 | 已定位待修（run26 收官窗；e2e 脚本资产雷非产品雷） | **e2e-r7-a0-provider-receipt-chain.sh phase7 两雷**：①探针查 rca_report 旧列名 package（现行 schema 为 package_json）→ psql ERROR column \"package\" does not exist；②假设「每 run 必有 report_publication 行」——同场景连跑时第二次起报告恒为 report_publication_loser（同 incident generation 发布位已被更早报告占据，落档不发布是仲裁语义正确行为），30s 轮询恒超时误报 FAIL | run26 全日志（phase7 ERROR 原文）+ report_publication 表 184 行历史 SENT 对照 + control-app 日志 report_publication_loser 事件行（report_id/incident/generation 全锚） | 老雷家族第六处（package→package_json）；脚本断言把「发布仲裁唯一赢家」当「每 run 必发」——与发布赢家仲裁语义冲突 | 修复候选：①探针 SQL 改 package_json；②phase7 断言改「report 行在+validation=STRUCTURE_VALIDATED 即过，publication 行仅同 incident 首跑断言」 | 同场景连跑的 e2e 必须按仲裁语义断言；探针 SQL 列名要与现行迁移对账 | 路径一 SUMMARY §一/§五；a0-path1-run26.log |",
"| BA-135 | 已立案待修（run16/17 实证） | **run 级工具 allowlist 拒绝走 UNKNOWN_TOOL 终止族——BoundedLlmRoleRunner 计步反馈环被绕过**：模型请求 allowlist 外工具时 Gateway 回 UNKNOWN_TOOL（ToolControlPlaneException）→ NativeInvestigationExecutor 直接收敛 DEAD，模型拿不到 TOOL_NOT_ALLOWED 软拒绝+修正指引，无重试机会即整任务终止 | run16/17（a0-path1-run16/17.log）：收紧 allowlist 后主任务连死于 UNKNOWN_TOOL→DEAD，账本零软拒绝记录；对照 INVALID_ARGS 走 advanceStep 计步重驱同窗实证可行 | 控制面异常两族分类面：run 级策略拒绝（模型可修正）与真未知工具（不可修正）共用 UNKNOWN_TOOL 一码——语义折叠 | 修复候选：策略拒绝细分 TOOL_NOT_ALLOWED 软族（模型可见、计步重驱、指引改选 allowlist 内工具），仅真未知工具保留终止族 | 拒绝面必须按「模型可否修正」分族——一切绕过反馈环的终止都是把可恢复错误当不可恢复 | 路径一 SUMMARY §五；BoundedLlmRoleRunner 软捕族；run16/17 |",
]

PROG_LINE = "| 2026-09-13 06:10 | 路径一收官（A0 参数化直读工具面，用户四路径裁定之一）：**run26 phase5 历史死门首破**——flagd paymentFailure=100% 真故障注入（S1/S2 侧车）+qwen 零委派+prompt v13，配方 4 工具全 SUCCESS（catalog/metric_value/logs.aggregate/logs.query），claim kind=ROOT_CAUSE status=TRUE lifecycle=ACTIVE evidence_basis=MULTI_SOURCE_CONSISTENT refs=2（checkout 调 PaymentService/Charge 因下游不可用失败=真实注入根因），phase6 回执链 PASS；phase7=脚本两雷（BA-134）系统无缺陷。四产品修复全 195 部署+本地测试绿：PrometheusApiExecutor catalog 改 /api/v1/series 流式抽名+metric_value 直查；AlertAm4Config 补 metric_value 装配行（run16/17 UNKNOWN_TOOL 真根因）；SingleToolEvidenceAgent NO_DATA 诚实透传（三层失真折叠拆除）；LogQueryExecutor render 全流式重写（run24 RESULT_OVERSIZE 终止族→行级 truncated 有界截断，run26 首战交付证据行）。prompt v8→v13 演进（四步配方+证据语义+rpc_client error_type 指标面）。环境定责两新面：prometheus 768m（300m 双峰时延 REMOTE 风暴）+glm-5 配额尽切 qwen。新立案 BA-133（logs.aggregate 无 error 过滤语义错位）/BA-134（A0 phase7 脚本两雷）/BA-135（allowlist 拒绝走 UNKNOWN_TOOL 终止族绕反馈环）。18 run 日志+runs+SUMMARY 归档 docs/测试证据/R7/runs/path1-a0-20260912/；195 姿态回收：paymentFailure=off+locust 3 用户基线 | 路径一执行者 |"


def append_block(path, lines):
    blob = "".join(l + "\n" for l in lines).encode("utf-8")
    with open(path, "rb") as f:
        wt = f.read()
    if not wt.endswith(b"\n"):
        wt += b"\n"
    if bytes(lines[-1], "utf-8") in wt:
        print("SKIP already in worktree:", path)
    else:
        with open(path, "wb") as f:
            f.write(wt + blob)
        print("worktree appended:", path)
    head = subprocess.run(["git", "show", "HEAD:" + path],
                          capture_output=True, check=True).stdout
    if not head.endswith(b"\n"):
        head += b"\n"
    staged = head + blob
    sha = subprocess.run(["git", "hash-object", "-w", "--stdin"],
                         input=staged, capture_output=True, check=True
                         ).stdout.decode().strip()
    subprocess.run(["git", "update-index", "--cacheinfo", "100644," + sha + "," + path],
                   check=True)
    print("index staged:", path, sha[:12])


append_block(BUG, BUG_LINES)
append_block(PROG, [PROG_LINE])
