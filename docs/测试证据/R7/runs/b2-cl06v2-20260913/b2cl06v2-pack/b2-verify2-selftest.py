#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""b2-verify2-selftest —— verify2 离线变异自测（S0/U01 验收 N01~N05/N08 离线面）。

对照 v1 假绿复现四案（docs/b2-verifier-audit-results.json）逐案断言 v2 退出码：
  1 happy          非空 witness 且父序保留        → PASS  0
  2 empty_parent   全边界父版恒空                  → INCONCLUSIVE 3（N01）
  3 digest_bad     prompt 字节改但 digest 保留     → FAIL  1（N02，v1 为 exit 0）
  4 lost_witness   跨轮后删指定反证                → FAIL  1（N03，v1 为 exit 0）
  5 reorder        父项顺序反转                    → FAIL  1（N04，v1 为 PASS）
  6 shape_bad      字段数不合形行                  → ERROR 2（N05：不静默丢）
  7 nested_wm      working_memory 嵌套对象可解析   → PASS  0（N08）
  8 single_round   仅一轮                          → FAIL  1
"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY2 = os.path.join(HERE, "b2-cl06-verify2.py")


def wm_json(slots):
    return json.dumps({"working_memory": slots, "note": "envelope"},
                      ensure_ascii=False)


def row(seq, rnd, text, digest=None):
    return {"seq": seq, "round": rnd, "level": "FULL", "text": text,
            "digest": digest or hashlib.sha256(text.encode("utf-8")).hexdigest()}


def slots_with(counter=None, gaps=None, ruled=None, hypo=None):
    return {"hypotheses": hypo or [], "ruled_out": ruled or [],
            "counter_evidence_refs": counter or [], "open_gaps": gaps or []}


def case_rows(name):
    wm_r0a = wm_json(slots_with())
    wm_r0b = wm_json(slots_with(counter=["ev-001"], gaps=["gap-logs"]))
    if name == "happy":
        wm_r1 = wm_json(slots_with(counter=["ev-001", "ev-002"], gaps=["gap-logs", "gap-cfg"]))
        wm_r2 = wm_json(slots_with(counter=["ev-001", "ev-002"],
                                   gaps=["gap-logs", "gap-cfg", "gap-new"]))
        return [row(0, 0, wm_r0a), row(1, 0, wm_r0b), row(2, 1, wm_r1), row(3, 2, wm_r2)]
    if name == "empty_parent":
        wm_r1 = wm_json(slots_with())
        return [row(0, 0, wm_r0a), row(1, 0, wm_json(slots_with())), row(2, 1, wm_r1)]
    if name == "digest_bad":
        wrong = wm_json(slots_with(counter=["ev-001"]))
        return [row(0, 0, wm_r0b, digest=hashlib.sha256(b"tampered").hexdigest()),
                row(1, 1, wrong)]
    if name == "lost_witness":
        wm_r1 = wm_json(slots_with(gaps=["gap-logs"]))  # ev-001 丢失
        return [row(0, 0, wm_r0b), row(1, 1, wm_r1)]
    if name == "reorder":
        wm_r1 = wm_json(slots_with(counter=["ev-002", "ev-001"]))  # 前缀反转
        return [row(0, 0, wm_json(slots_with(counter=["ev-001", "ev-002"]))),
                row(1, 1, wm_r1)]
    if name == "shape_bad":
        rows = [row(0, 0, wm_r0b), row(1, 1, wm_json(slots_with(counter=["ev-001"])))]
        broken = {"seq": 2, "round": 1, "level": "FULL", "text": "missing-digest"}
        rows.append(broken)  # 缺 digest 键 → 信封读入 ERROR（不合形不静默丢）
        return rows
    if name == "nested_wm":
        wm = json.dumps({"working_memory": slots_with(counter=["ev-001"]),
                         "nested": {"deep": {"x": 1}}}, ensure_ascii=False)
        return [row(0, 0, wm_json(slots_with())), row(1, 0, wm), row(2, 1, wm)]
    if name == "single_round":
        return [row(0, 0, wm_r0a), row(1, 0, wm_r0b)]
    raise SystemExit("unknown case " + name)


EXPECT = {"happy": 0, "empty_parent": 3, "digest_bad": 1, "lost_witness": 1,
          "reorder": 1, "shape_bad": 2, "nested_wm": 0, "single_round": 1}

tmp = tempfile.mkdtemp(prefix="verify2-selftest-")
ok_all = True
for name in sorted(EXPECT):
    env = os.path.join(tmp, name + ".json")
    with open(env, "w", encoding="utf-8") as f:
        json.dump(case_rows(name), f, ensure_ascii=False)
    out = os.path.join(tmp, name + "-out")
    p = subprocess.Popen([sys.executable, VERIFY2, "--envelope", env, "--out", out],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    so, _ = p.communicate()
    got = p.returncode
    head = so.decode("utf-8", "replace").strip().splitlines()
    verdict = "OK " if got == EXPECT[name] else "BAD"
    if got != EXPECT[name]:
        ok_all = False
    print("%s %-13s expect=%d got=%d  %s" % (verdict, name, EXPECT[name], got,
                                             head[0] if head else ""))

print("SELFTEST-" + ("PASS" if ok_all else "FAIL"))
sys.exit(0 if ok_all else 1)
