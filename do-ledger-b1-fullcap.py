# -*- coding: utf-8 -*-
# B1-1 收官落账双写：工作树追加我的行；index 构造 HEAD+我的行
import subprocess

PROG = "docs/告警-PROGRESS.md"

PROG_LINE = "| 2026-09-13 11:05 | B1-1 收官（输入捕获 FULL 档真跑+CL-03 入模实证，195 真机）：compose override 开 APP_ALERT_R7_INPUTCAPTURE=full（机制教训=.env 键无 compose 映射行不进容器，两度被对方重建窗冲掉后改 override 才稳，关联 BA-137）；run 6b607ec9 SUCCEEDED phase1-6 PASS（phase6 账面自含\"捕获恰一行+digest 对账一致\"；phase7=已知 BA-134 脚本雷非系统缺陷）；**5/5 primary 行 FULL 原文落库（5598~15610B）**，sha256(原文)==prompt_digest 逐行对账全 PASS，CL-03 分型投影在活 prompt 全文断言通过（observations/message/labels/window/total_count 全在，\"(+N struct fields)\" 旧折叠零残留，truncated+omission_reason=ITEM_LIMIT 截断留痕实拍），RR16 模型输入面真集合复扫 5 文件 0 秘密；复原绿（override 摘除回 DIGEST_ONLY 默认+flagd paymentFailure=off，容器 env 复核留档）。台账升级：CL-03→VERIFIED_TARGET、R2/V90 FULL 档 VERIFIED_TARGET、RR16 两面全收、不确定项 N 关闭。断言器两修：195 py3.6 无 capture_output→Popen+PIPE；prompt 含原生换行须 psql -F \\x1f -R \\x1e 记录分隔（按行 split 撕裂记录=首跑零行假象）。证据 docs/测试证据/R7/runs/b1-input-capture-20260913/（5 prompt 全文+断言输出+A0 全 phase 工件+脚本可复现链；证据包密钥红线：env dump 6 敏感键+3 裸 hex 机器 Bearer 全打码，终扫 FINAL-SCAN-PASS） | B1执行者 |"


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


append_block(PROG, [PROG_LINE])
