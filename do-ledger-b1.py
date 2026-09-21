# -*- coding: utf-8 -*-
# B1 落账双写：工作树追加我的行；index 构造 HEAD+我的行（提交自包含，不裹挟对方 WIP 行）
import subprocess

BUG = "docs/告警-BUGLOG.md"
PROG = "docs/告警-PROGRESS.md"

BUG_LINES = [
 "| BA-138 | 已修复关单（bcda248；RR03 195 真机验收） | **audit-runtime.sh docker 面采集失败（零容器）时 container_mem_limits 误判 MATCH（“0 容器全有上限”）**——采集失败冒充一致，恰为 RR03 要防的失效模式：对拍器出口 exit 0 会放行一次实际未采集的“一致” | RR03 前置复核：DOCKER_HOST=不可达端点纯采集 containers=0；修复前代码路径 no_limit=[] 空列表落入 MATCH 分支（b1-or01-rr-20260913/rr03/runtime-manifest-broken.json 对照） | 对拍 verdict 只看「无违规项」不看「采集面是否在场」——空集与全过的语义折叠 | 空容器列表改判 UNKNOWN（“docker 面采集失败或无运行容器——不可判一致（RR03）”）；flyway QUERY_FAILED/skillbind UNKNOWN 原语义已对无需动；修复后 RR03 全 UNKNOWN/overall=UNKNOWN/exit 1/零秘密 PASS | 验收工具自身的失效模式必须先于用它做的验收被修——RR03 先修后验，不可用带病对拍器给自己发通行证 | docs/测试证据/R7/runs/b1-or01-rr-20260913/（RR02-04 执行记录）；commit bcda248 |",
]

PROG_LINE = "| 2026-09-13 11:05 | 总方案 B1 正确性批（进行中；入口=总方案 v1 §17）：**RR02/03/04 全 VERIFIED_TARGET**——RR03 先修后验（BA-138：采集器 docker 面零容器误判 MATCH→UNKNOWN @bcda248；不可达 DOCKER_HOST 独立进程下全 UNKNOWN、overall=UNKNOWN、exit 1、输出零秘密）；RR02 期望面注入 V999→flyway DRIFT 可见+exit 1 阻断，附真实 V108 漂移当场定性（=对方 SR 批 22:18Z 部署推进运行面、当前构建树↔库 81 版本 MATCH，B0 冻结面过期非真漂移）；RR04 rca_run_skill_binding 9 行各钉各 release_digest+config_digest 多版本并存、两停滞 run 老身份不被改写（Skill ACTIVE 双版本半面 N/A：当前全 NONE 绑定，如实登记待 CL-09 链）。**OR-04 交付**：docs/告警-OR04身份角色端点矩阵-v1.md（6 身份线×路径矩阵×CSRF 豁免分工+弱口令核查口径）+RR13 11/11 PASS（deny-by-default/四线互不越权/越权写角色层即拒零副作用/CSRF 保留面/SSE 票必经）+RR16 日志面 PASS（10 秘密值×control/notify/web 三容器日志=0 出现）。**A13-01 真实入模窗排队**：V90 捕获全库 230 行均 DIGEST_ONLY（run26 五 prompt 正文不可回放，台账 N 项）→FULL 档 override 与树外 e2e 副本脚本已备（b1-fullcap-main.sh）；两次撞对方部署窗（22:18Z V99~V108 部署、02:30Z BA-136/137 修复重打包）INPUTCAPTURE env 被冲（.env 键被对方重建吸收但 compose 无映射行=机制事实），静默侦测器在跑；CL-03 已实证上靶（部署 jar projectLogs/projectMetrics 在、appendTopLevel 无）。协作事实对齐：本批观察的 reconciler 热循环与 .env 消失+CRLF=对方 BA-136/137 已立案闭环不重复立案。证据 docs/测试证据/R7/runs/b1-or01-rr-20260913/ + b1-or04-rr13-20260913/ | B1 执行者 |"


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
