# -*- coding: utf-8 -*-
# 追加 2026-09-20 晚间行（旗标前提证伪+质量门双批+推送手术）到 PROGRESS 里程碑表，只 stage 本行
import io, subprocess

path = r"docs/告警-PROGRESS.md"
with io.open(path, "r", encoding="utf-8") as f:
    lines = f.readlines()

last_idx = None
for i, ln in enumerate(lines):
    if ln.startswith("| 2026-"):
        last_idx = i
assert last_idx is not None

row = ("| 2026-09-20 23:5x | **推送手术+双测试收官（用户令：推送分支/污染条件全链/质量门双批，并行会话已停）**："
       "**①推送手术**——feat/a-batch-readfaces 推送两次 HTTP 500 定谳：未推送增量含 3.4GB blob "
       "diag2.tar.gz（9a0b2916 全树同步提交混入，GitHub 单文件上限拒收）。backup 分支+stash（并行会话 WIP "
       "入 stash）后 filter-branch --index-filter 重写未推送 180 提交摘除该 blob（增量 3469MiB→67MiB），"
       "代理 a58de58a..fd2922b0 推送成功，stash pop 无损恢复工作区（并行会话未提交行原样保留）；"
       "backup/pre-blob-rewrite 分支+refs/original 留档。"
       "**②Test A 前提证伪（重要修正）**——「开 paymentFailure 使 F1 重复单滞留 CREATED」前提不成立："
       "arena 支付模拟器不受 flagd 旗标管（50% 窗口实测重复单全部 AUTH/SUCCEEDED 推进 ENABLED，"
       "旗标只作用于 otel-demo checkout 栈）；批 348e9ba5 五案例仍全 DECIDABLE 全命中。F1 窗口内清偿的"
       "自然触发条件=AUTH 不即时成功（UNKNOWN/组合故障），修复面维持活体实验证据；旗标已还原 off。"
       "**③Test B 质量门双批**——首次踩坑：不带 panel=全量 25 场景口径（误发批卡 flagd 场景取消后 "
       "worker_lost 诚实落 FAILED）；正确口径 panel=SMOKE×2 轮：B1'=f6b8248f 与 "
       "B2=61b9dc4f（链式自动接力，BA-190 按批派生 tag 实证）双双 **10/10 全 DECIDABLE**，"
       "自动对比较 baseline 选择正确、**配对 10/10 全配对**（锚点容差+全报告基本面全通），"
       "门仍 INCONCLUSIVE——**最后一个阻塞点精确隔离为 IDENTITY_UNVERIFIED**：contentDigest 来自"
       "数据集案例身份解析（CaseIdentityRow→dataset case versions），注入型场景批案例未经 "
       "RegressionCaseAdmission 准入故身份不可解析（EvalCompare.java:275 任侧 null→UNVERIFIED）；"
       "修复=注入场景的案例准入/身份映射设计项，非配置可解，单列下窗。"
       "**④运维面**——persist 口令后发批脚本简化（重启 control-app 载入 Demo#0917 即可，舞步退役）；"
       "收尾 FIRING=基线 1 条、无 ACTIVE/RECOVERING 会话、无遗留 watcher。| 主会话 |\n")

lines.insert(last_idx + 1, row)
with io.open(path, "w", encoding="utf-8", newline="") as f:
    f.writelines(lines)

# 只 stage 本行（并行会话若有未提交行则不碰）
diff = subprocess.run(["git", "diff", "--", path], capture_output=True).stdout.decode("utf-8")
out = []
kept = []
old_start = new_start = old_count = new_count = 0
started = False
marker = "推送手术+双测试收官"
for ln in diff.splitlines():
    if ln.startswith("@@"):
        if kept:
            out.append("@@ -%d,%d +%d,%d @@" % (old_start, old_count, new_start, new_count))
            out.extend(kept)
            kept = []
        body = ln.split("@@")[1].strip()
        old_part, new_part = body.split("+")
        old_start = int(old_part.replace("-", "").split(",")[0])
        new_start = int(new_part.split(",")[0])
        old_count = new_count = 0
        started = True
        continue
    if not started:
        continue
    if ln.startswith(("diff ", "index ", "--- ", "+++ ")):
        continue
    if ln.startswith("+") and marker in ln:
        kept.append(ln)
        new_count += 1
    elif ln.startswith("+"):
        continue
    elif ln.startswith("-"):
        kept.append(ln)
        old_count += 1
    else:
        kept.append(ln)
        old_count += 1
        new_count += 1
if kept:
    out.append("@@ -%d,%d +%d,%d @@" % (old_start, old_count, new_start, new_count))
    out.extend(kept)
patch = "diff --git a/%s b/%s\n--- a/%s\n+++ b/%s\n%s" % (path, path, path, path, "\n".join(out) + "\n")
with io.open("tmp_p12_ledger.patch", "w", encoding="utf-8", newline="\n") as f:
    f.write(patch)
print("row inserted at", last_idx + 2, "; patch hunks:", len(out))
