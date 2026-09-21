# -*- coding: utf-8 -*-
# B0 落账：工作树追加我的行；index 构造 HEAD+我的行（对方 WIP 条目留工作树）
import subprocess

PROG = "docs/告警-PROGRESS.md"
LINE = "| 2026-09-13 07:05 | 总方案 B0 事实冻结批收官（执行入口=docs/告警-Agent生产级收口与后续优化总方案-v1.md）：OR-01 采集器 deploy/audit-runtime.sh 落库（只读/脱敏白名单/prompt 只记 sha256/三模式 emit-collect-compare/UNKNOWN≠MATCH 退出码纪律）+RR01 195 真机验收 overall=UNKNOWN 无 DRIFT（flyway 80 版本 MATCH 含 V99~V107、6 容器内存上限 MATCH、skill_binding 9 行 MATCH；诚实 UNKNOWN 两项=镜像无 commit 身份→OR-11 缺口实证+BUDGET_STEP 部署层未钉走默认）；A13 六项复核=CL-03/05/01-02/06/07-08/09 实现覆盖全定位（围栏/Skill 绑定/压缩台账/curation 四面已 VERIFIED_TARGET）；HOST2 现场核对修正过时假设：gatus（digest 锁定 a8c53f9e）+duty-adapter+node-exporter 在 117.72.208.68 运行中、「探针未部署」作废；duty snapshot 502 三次=部署窗瞬态已自愈（OR-02 复验登记）；CL/OP/OR 完成台账 v1 初始化（§18 格式 31 卡+RR 矩阵+A13 映射+不确定项 G~K 六项）；证据 docs/测试证据/R7/runs/or01-runtime-audit-20260913/ | B0 执行者 |"

blob = (LINE + "\n").encode("utf-8")
with open(PROG, "rb") as f:
    wt = f.read()
if not wt.endswith(b"\n"):
    wt += b"\n"
marker = "总方案 B0 事实冻结批收官"
if marker.encode("utf-8") in wt:
    print("SKIP already in worktree")
else:
    with open(PROG, "wb") as f:
        f.write(wt + blob)
    print("worktree appended")
head = subprocess.run(["git", "show", "HEAD:" + PROG], capture_output=True, check=True).stdout
if not head.endswith(b"\n"):
    head += b"\n"
sha = subprocess.run(["git", "hash-object", "-w", "--stdin"], input=head + blob,
                     capture_output=True, check=True).stdout.decode().strip()
subprocess.run(["git", "update-index", "--cacheinfo", "100644," + sha + "," + PROG], check=True)
print("index staged:", sha[:12])
