#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# B2 CL-06 跨轮 prompt 可达性断言（FULL 捕获；195 python3.6 兼容）
# 核心断言：primary 任务 round>0 首个 prompt 的 working_memory 槽 ⊇ round-0 末 prompt 槽
# （父版槽在前+delta 去重并集——反证跨轮可达的活 prompt 实证）
import hashlib, json, re, subprocess

run = open("/tmp/b2cl06/run-id.txt").read().strip().split("=", 1)[1]
q = ("select c.action_seq, c.round_id, i.capture_level, i.prompt_text, i.prompt_digest "
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
    if len(f) == 5:
        rows.append(f)
print("run=%s primary FULL 捕获行数=%d" % (run, len(rows)))
assert rows, "FAIL: 无 primary 捕获行"

WM_RE = re.compile(r'"working_memory":\s*(\{[^{}]*\})')

ok = True
by_round = {}
for seq, rnd, level, text, dig in rows:
    rnd = int(rnd)
    if level != "FULL" or not text:
        print("  [seq=%s] FAIL: level=%s" % (seq, level)); ok = False; continue
    h = hashlib.sha256(text.encode("utf-8")).hexdigest()
    d_ok = h == (dig or "").strip().lower()
    m = WM_RE.search(text)
    wm = m.group(1) if m else None
    try:
        slots = json.loads(wm) if wm else None
    except Exception:
        slots = None
    print("  [seq=%s round=%d] digest=%s working_memory=%s" % (
        seq, rnd, "PASS" if d_ok else "FAIL", "in" if slots else "MISSING"))
    if not d_ok or slots is None:
        ok = False
    by_round.setdefault(rnd, []).append((int(seq), slots))
    with open("/tmp/b2cl06/prompt-r%d-s%s.txt" % (rnd, seq), "w", encoding="utf-8") as f:
        f.write(text)

rounds = sorted(by_round)
print("rounds=%s" % rounds)
if len(rounds) < 2:
    print("  FAIL: 无跨轮（round 数 %d <2）——CL-06 不可验" % len(rounds))
    ok = False
else:
    for r0, r1 in zip(rounds, rounds[1:]):
        last0 = sorted(by_round[r0])[-1][1] or {}
        first1 = sorted(by_round[r1])[0][1] or {}
        for key in ("hypotheses", "ruled_out", "counter_evidence_refs", "open_gaps"):
            prev = last0.get(key, [])
            cur = first1.get(key, [])
            missing = [x for x in prev if x not in cur]
            if missing:
                print("  FAIL: round%d→%d 槽 %s 丢失 %d 项（反证/缺口跨轮不可达）: %s"
                      % (r0, r1, key, len(missing), str(missing)[:200]))
                ok = False
            elif prev:
                print("  round%d→%d 槽 %s：父版 %d 项全保留（并集可达）"
                      % (r0, r1, key, len(prev)))
            else:
                print("  round%d→%d 槽 %s：父版为空（无丢失面）" % (r0, r1, key))
        # 父版槽在前（mergeAccumulated 语义抽查：首轮槽前缀序）
        for key in ("counter_evidence_refs", "ruled_out"):
            prev = last0.get(key, [])
            cur = first1.get(key, [])
            if prev and cur[:len(prev)] == prev:
                print("  round%d→%d 槽 %s：父版在前序成立" % (r0, r1, key))
# 槽纯度（prompt 面复扫：控制拒绝码不得入业务槽）
for rnd in rounds:
    for seq, slots in by_round[rnd]:
        if not slots:
            continue
        joined = json.dumps(slots, ensure_ascii=False)
        for bad in ("TOOL_NOT_ALLOWED", "INVALID_ARGS", "DECISION_UNPARSEABLE"):
            if bad in joined:
                print("  FAIL: round%d seq%s 槽混入 %s" % (rnd, seq, bad)); ok = False
print("ASSERT-OVERALL:", "PASS" if ok else "FAIL")
