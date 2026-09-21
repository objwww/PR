# -*- coding: utf-8 -*-
# B2-1 PROGRESS 混权双写：工作树追加我的行（保留对方 WIP 行）+ index blob=HEAD+我的行
import subprocess, sys

PATH = "docs/告警-PROGRESS.md"

ENTRY = (
    "| 2026-09-13 13:10 | B2 合窗第一批收官（CL-04/CL-06 验收+CL-06 跨轮结构真机实证；总方案=docs/告警-Agent生产级收口与后续优化总方案-v1.md B2 批）："
    "**CL-04**→VERIFIED_TARGET（MC 矩阵对位：本地 53/53+195 树 58/58——mc10 输入超限零触网/mc11 估算边界/r2_full|r2_digestOnly|r2_redacted 三档/越权零读取/r10 failFast；证据 b2-cl0406-it.log）；"
    "**CL-06**→VERIFIED_TARGET（持久面 PostgresWorkingMemoryIT 5/5；跨轮结构 **run 1a5175cb SUITE PASS** phase1-9 全绿：委派 1 子任务 round 0→1、记忆行 3=rev1(无父首行)→rev2→rev3 父链连号+锚点回环（checkpoint 锚最新行+digest）+槽纯度（控制拒绝码零混入）+FULL 捕获恰一行对账+诚实 UNKNOWN 双分支+phase7 loser 面（BA-134 第二雷仲裁语义真机再确认）；跨轮 prompt 可达性 verify.py PASS=5 笔 primary FULL digest 全对账+rounds=[0,1]+槽并集「父版为空（无丢失面）」——**委派获批先耗版次故首记忆行落 rev>=1、final 亦耗版次故 checkpoint 终版>末行版次，driver 父链/锚点两断言据此委派流校准=测试面修正非产品缺陷**）。"
    "八轮迭代史全落档：缺省 prompt 零委派→硬性委派序 v3→证据行恒零→rpc 配方嫁接+诚实未决分支→BA-134 loser→记忆行=1（委派步不 advanceStep 不写记忆行=既定语义）→v4 两步自查→父链/锚点两断言校准。"
    "**诚实边界**：非空跨轮槽流动需多委派批（单批窗数据面不达；代码面 mergeAccumulated 父版槽在前+delta 去重与 IT 已覆盖）→RR 窗排队。"
    "**复原绿**：零委派姿态+INPUTCAPTURE 回 digest-only+prompt 回四工具配方+flagd paymentFailure off+deploy/.env 惰性残留行 R7_INPUTCAPTURE=full 清除（无 compose 映射，BA-137 同型不进容器）。"
    "证据=docs/测试证据/R7/runs/b2-cl06-20260913/（601 件：30 run 目录+八轮日志+scripts+记忆链/锚点/verify 三面定格；密钥终扫双面绿——.env 实值定向密钥族零命中（11 命中全为被测旋钮非敏感标量）+词形零命中）。"
    "随窗落档 docs/告警-B2-RR21-28用例设计方案-v1.md（RR21 依赖故障有界/RR22 harness 复用/RR23 loki 停/RR24 BASE_URL 黑洞/RR25 搭窗顺跑/RR26 locust 阶梯/RR27/RR28 notify 黑洞恢复）待独立注入窗 | 主会话 |\n"
)

def run(args, data=None):
    p = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE)
    out, err = p.communicate(data)
    if p.returncode != 0:
        sys.stderr.write(err.decode("utf-8", "replace"))
        sys.exit("FAIL: " + " ".join(args[:3]))
    return out

entry_b = ENTRY.encode("utf-8")

# 1) 工作树追加（保留对方 WIP 行）
with open(PATH, "ab") as f:
    cur = open(PATH, "rb").read()
    if cur and not cur.endswith(b"\n"):
        f.write(b"\n")
    f.write(entry_b)
print("worktree-appended")

# 2) index blob = HEAD 内容 + 我的行（不含对方 WIP 行）
head = run(["git", "show", "HEAD:" + PATH])
blob = head + entry_b if head.endswith(b"\n") else head + b"\n" + entry_b
sha = run(["git", "hash-object", "-w", "--stdin"], blob).decode().strip()
run(["git", "update-index", "--cacheinfo", "100644", sha, PATH])
print("index-blob=%s" % sha)
