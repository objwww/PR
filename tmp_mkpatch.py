# -*- coding: utf-8 -*-
# 只把 FUP-04(a) 那一行 stage 进 index（并行会话未提交行不碰）
import io, subprocess

path = "docs/告警-PROGRESS.md"
marker = "FUP-04(a) F1 窗口内清偿落地并实机验证"

diff = subprocess.run(["git", "diff", "--", path], capture_output=True).stdout.decode("utf-8")
lines = diff.splitlines()

out = []
header_done = False
old_count = 0
new_count = 0
kept = []
for ln in lines:
    if ln.startswith("@@"):
        # 开新 hunk：旧头先丢弃重算
        if kept:
            # 结算上一个 hunk
            out.append("@@ -%d,%d +%d,%d @@" % (old_start, old_count, new_start, new_count))
            out.extend(kept)
            kept = []
        body = ln.split("@@")[1].strip()  # e.g. -1194,3 +1194,16
        old_part, new_part = body.split("+")
        old_start = int(old_part.replace("-", "").split(",")[0])
        new_start = int(new_part.split(",")[0])
        old_count = 0
        new_count = 0
        header_done = True
        continue
    if not header_done:
        continue
    if ln.startswith("diff ") or ln.startswith("index ") or ln.startswith("--- ") or ln.startswith("+++ "):
        continue
    if ln.startswith("+") and marker in ln:
        kept.append(ln)
        old_count += 0
        new_count += 1
    elif ln.startswith("+"):
        continue  # 并行会话的未提交行：不进 patch
    elif ln.startswith("-"):
        kept.append(ln)
        old_count += 1
        new_count += 0
    else:
        kept.append(ln)
        old_count += 1
        new_count += 1

if kept:
    out.append("@@ -%d,%d +%d,%d @@" % (old_start, old_count, new_start, new_count))
    out.extend(kept)

patch = "\n".join(out) + "\n"
full = "diff --git a/%s b/%s\n--- a/%s\n+++ b/%s\n%s" % (path, path, path, path, patch)
with io.open("tmp_p10_only.patch", "w", encoding="utf-8", newline="\n") as f:
    f.write(full)
print("patch lines:", len(out))
