#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# B1-1 断言器 v2（195 python3.6 兼容）：FULL 捕获 prompt 的 CL-03 投影/digest/零旧折叠
# 解析：-F \x1f 字段分隔 + -R \x1e 记录分隔（prompt_text 含原生换行，不能按行 split）
import hashlib, subprocess

run = open("/tmp/b1fullcap/run-id.txt").read().strip().split("=", 1)[1]
q = ("select c.action_seq, i.capture_level, i.prompt_text, i.prompt_digest "
     "from rca_model_input i join rca_model_call c on c.id=i.model_call_id "
     "where c.run_id='%s' and c.role_id='primary' order by c.action_seq") % run
p = subprocess.Popen(["docker", "exec", "deploy-postgres-1", "psql", "-U",
                      "postgres", "-d", "pr_agent", "-At",
                      "-F", "\x1f", "-R", "\x1e", "-c", q],
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
out = p.communicate()[0].decode("utf-8", "replace")

rows = []
for rec in out.split("\x1e"):
    rec = rec.rstrip("\n")
    if not rec.strip():
        continue
    f = rec.split("\x1f")
    if len(f) == 4:
        rows.append(f)
print("run=%s" % run)
print("primary FULL 捕获行数: %d" % len(rows))
assert rows, "FAIL: 无 primary 捕获行"

ok = True
texts = []
for seq, level, text, dig in rows:
    if level != "FULL" or not text:
        print("  [seq=%s] FAIL: level=%s text_len=%d" % (seq, level, len(text or "")))
        ok = False
        continue
    texts.append((seq, text, dig.strip()))
    h = hashlib.sha256(text.encode("utf-8")).hexdigest()
    d_ok = h == (dig or "").strip().lower()
    env_ok = "valid_artifact_refs" in text
    proj_logs = '"observations"' in text
    proj_metr = '"labels"' in text
    no_old = "struct fields)" not in text
    ev_ok = ("Payment" in text) or ("error_type" in text) or ("payment" in text)
    print("  [seq=%s] bytes=%d digest=%s envelope=%s logs投影=%s metrics投影=%s 旧折叠残留=%s 真实证据=%s" % (
        seq, len(text.encode("utf-8")), "PASS" if d_ok else "FAIL",
        "PASS" if env_ok else "--", "PASS" if proj_logs else "--",
        "PASS" if proj_metr else "--", "无" if no_old else "有!",
        "PASS" if ev_ok else "FAIL"))
    if not d_ok or not env_ok or not ev_ok or not no_old:
        ok = False
    with open("/tmp/b1fullcap/prompt-%s.txt" % seq, "w", encoding="utf-8") as f:
        f.write(text)

any_logs = any('"observations"' in t for _, t, _ in texts)
any_metr = any('"labels"' in t for _, t, _ in texts)
any_msg = any('"message"' in t for _, t, _ in texts)
any_win = any('"window"' in t for _, t, _ in texts)
any_tot = any('"total_count"' in t for _, t, _ in texts)
print("投影标记汇总: observations=%s message=%s labels=%s window=%s total_count=%s"
      % (any_logs, any_msg, any_metr, any_win, any_tot))
if texts and not (any_logs and any_msg and any_metr):
    print("  FAIL: CL-03 投影标记不全")
    ok = False
print("ASSERT-OVERALL:", "PASS" if ok else "FAIL")

if texts:
    t = texts[-1][1]
    i = t.find('"observations"')
    if i >= 0:
        print("---- evidence 投影样本（末轮 prompt 截取）----")
        print(t[max(0, i - 100): i + 800].replace("\n", " ")[:900])
