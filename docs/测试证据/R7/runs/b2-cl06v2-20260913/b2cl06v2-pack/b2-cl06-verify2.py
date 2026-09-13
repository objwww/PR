#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""b2-cl06-verify2 —— CL-06 跨轮 prompt 可达性验证器 v2（S0/U01 修复版）。

v1 缺陷（docs/告警-BUGLOG与B2测试审查-统一收口技术方案-v1.md §4.1，离线复现
b2-verifier-audit-results.json 四案）：FAIL 只打印不置退出码（exit 0 假绿）、父序
反转静默过、空父集算语义通过、平面对象正则误抽。

v2 契约：
  退出码 PASS=0 / FAIL=1 / ERROR=2 / INCONCLUSIVE=3（优先级 ERROR>FAIL>INCONCLUSIVE）
  - ERROR：DB 调用失败/返回码非零/字段数不合形行不静默丢/信封不可解析
  - FAIL：digest 不对账、round<2、跨轮槽丢失、父序违反冻结契约、槽混控制拒绝码
  - INCONCLUSIVE：结构全过但所有轮边界父版四槽恒空——非空 witness 未取得，
    不得冒充语义通过（N01/N03）
  - PASS：结构全过且至少一个边界存在非空父版槽被完整保留（非空 witness）

用法：
  DB 模式：python3 b2-cl06-verify2.py --run-id <uuid> --out <dir>
  离线模式：python3 b2-cl06-verify2.py --envelope <rows.json> --out <dir>
    rows.json = [{"seq","round","level","text","digest"}, ...]

产物：<out>/verify2-result.json（suiteVersion/runId/status/assertions/coverage/
configDigest/evidenceRefs）+ prompt-rN-sM.txt 逐行原文。195 python3.6 兼容。
"""
import hashlib
import json
import os
import subprocess
import sys

SUITE_VERSION = "b2-cl06-verify2/1.0"
SLOTS = ("hypotheses", "ruled_out", "counter_evidence_refs", "open_gaps")
ORDER_CONTRACT_SLOTS = ("counter_evidence_refs", "ruled_out")  # 父版在前（冻结契约）
CONTROL_CODES = ("TOOL_NOT_ALLOWED", "INVALID_ARGS", "DECISION_UNPARSEABLE")

EXIT_PASS, EXIT_FAIL, EXIT_ERROR, EXIT_INCONCLUSIVE = 0, 1, 2, 3


class Verdict(object):
    def __init__(self):
        self.assertions = []
        self.failed = False
        self.errored = False

    def add(self, aid, status, detail):
        self.assertions.append({"id": aid, "status": status, "detail": detail})
        if status == "FAIL":
            self.failed = True
        elif status == "ERROR":
            self.errored = True

    def final(self, witness_seen):
        if self.errored:
            return "ERROR", EXIT_ERROR
        if self.failed:
            return "FAIL", EXIT_FAIL
        if not witness_seen:
            return "INCONCLUSIVE", EXIT_INCONCLUSIVE
        return "PASS", EXIT_PASS


def extract_json_object(text, key):
    """从 text 定位 "key": 后首个完整 JSON 对象（括号配平+字符串转义感知，
    支持嵌套——替代 v1 只匹配平面对象的正则）。"""
    marker = '"%s"' % key
    i = text.find(marker)
    if i < 0:
        return None, "key '%s' not found" % key
    j = text.find("{", i + len(marker))
    if j < 0:
        return None, "no object after key '%s'" % key
    depth, k, instr, esc = 0, j, False, False
    while k < len(text):
        c = text[k]
        if instr:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[j:k + 1]), None
                    except ValueError as e:
                        return None, "json parse fail @%d: %s" % (j, e)
        k += 1
    return None, "unbalanced object after key '%s'" % key


def db_rows(run_id, v):
    q = ("select c.action_seq, c.round_id, i.capture_level, i.prompt_text, i.prompt_digest "
         "from rca_model_input i join rca_model_call c on c.id=i.model_call_id "
         "where c.run_id='%s' and c.role_id='primary' order by c.action_seq") % run_id
    p = subprocess.Popen(
        ["docker", "exec", "deploy-postgres-1", "psql", "-U", "postgres",
         "-d", "pr_agent", "-At", "-F", "\x1f", "-R", "\x1e", "-c", q],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out, err = p.communicate()
    if p.returncode != 0:
        v.add("A00-db", "ERROR", "psql returncode=%d stderr=%s"
              % (p.returncode, err.decode("utf-8", "replace")[:300]))
        return []
    rows, bad = [], 0
    for rec in out.decode("utf-8", "replace").split("\x1e"):
        rec = rec.rstrip("\n")
        if not rec.strip():
            continue
        f = rec.split("\x1f")
        if len(f) != 5:
            bad += 1
            continue
        rows.append(f)
    if bad:
        v.add("A00-shape", "ERROR", "%d 行字段数≠5（不静默丢弃）" % bad)
    return rows


def main():
    args = sys.argv[1:]
    run_id, envelope, out_dir = None, None, "/tmp/b2cl06"
    i = 0
    while i < len(args):
        if args[i] == "--run-id":
            run_id = args[i + 1]; i += 2
        elif args[i] == "--envelope":
            envelope = args[i + 1]; i += 2
        elif args[i] == "--out":
            out_dir = args[i + 1]; i += 2
        else:
            print("unknown arg %s" % args[i]); return EXIT_ERROR
    v = Verdict()
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)

    if envelope:
        try:
            with open(envelope, "r", encoding="utf-8") as f:
                rows = [ [str(r["seq"]), str(r["round"]), r["level"], r["text"], r["digest"]]
                         for r in json.load(f) ]
        except Exception as e:
            v.add("A00-envelope", "ERROR", "envelope read fail: %s" % e)
            rows = []
    else:
        if not run_id:
            print("--run-id 或 --envelope 必填"); return EXIT_ERROR
        rows = db_rows(run_id, v)

    if not v.errored:
        v.add("A01-rows", "PASS" if rows else "FAIL",
              "primary 捕获行=%d" % len(rows))

    by_round, digests = {}, []
    for seq, rnd, level, text, dig in rows or []:
        digest_ok = hashlib.sha256((text or "").encode("utf-8")).hexdigest() \
            == (dig or "").strip().lower()
        digests.append((dig or "").strip().lower())
        if level != "FULL" or not text:
            v.add("A02-capture-%s" % seq, "FAIL", "level=%s len=%d" % (level, len(text or "")))
        elif not digest_ok:
            v.add("A02-digest-%s" % seq, "FAIL", "sha256(text)≠prompt_digest")
        wm, wm_err = extract_json_object(text or "", "working_memory")
        if wm is None:
            v.add("A03-wm-%s" % seq, "FAIL", "working_memory 不可解析: %s" % wm_err)
            wm = {}
        else:
            missing = [s for s in SLOTS if s not in wm]
            if missing:
                v.add("A03-slots-%s" % seq, "FAIL", "缺槽 %s" % missing)
        try:
            rnd_i = int(rnd)
        except ValueError:
            v.add("A03-round-%s" % seq, "ERROR", "round 非整数: %r" % rnd); continue
        by_round.setdefault(rnd_i, []).append((int(seq), wm))
        if not envelope:
            with open(os.path.join(out_dir, "prompt-r%d-s%s.txt" % (rnd_i, seq)),
                      "w", encoding="utf-8") as f:
                f.write(text or "")

    rounds = sorted(by_round)
    if len(rounds) < 2:
        v.add("A04-rounds", "FAIL", "round 数 %d <2（CL-06 不可验）" % len(rounds))
    else:
        v.add("A04-rounds", "PASS", "rounds=%s" % rounds)

    witness_seen = False
    for r0, r1 in zip(rounds, rounds[1:]):
        last0 = sorted(by_round[r0])[-1][1]
        first1 = sorted(by_round[r1])[0][1]
        boundary_nonempty = False
        for key in SLOTS:
            prev, cur = last0.get(key) or [], first1.get(key) or []
            missing = [x for x in prev if x not in cur]
            if missing:
                v.add("A05-contain-r%d-%d-%s" % (r0, r1, key), "FAIL",
                      "丢失 %d 项（跨轮不可达）: %s" % (len(missing), str(missing)[:160]))
            elif prev:
                boundary_nonempty = True
                v.add("A05-contain-r%d-%d-%s" % (r0, r1, key), "PASS",
                      "父版 %d 项全保留" % len(prev))
            else:
                v.add("A05-contain-r%d-%d-%s" % (r0, r1, key), "SKIP",
                      "父版为空（无丢失面，不计语义覆盖）")
        # 冻结契约：counter_evidence_refs/ruled_out 父版在前（mergeAccumulated 语义）
        for key in ORDER_CONTRACT_SLOTS:
            prev, cur = last0.get(key) or [], first1.get(key) or []
            if prev:
                if cur[:len(prev)] == prev:
                    v.add("A06-order-r%d-%d-%s" % (r0, r1, key), "PASS",
                          "父版在前序成立（%d 项）" % len(prev))
                else:
                    v.add("A06-order-r%d-%d-%s" % (r0, r1, key), "FAIL",
                          "父项顺序反转/被改写（冻结契约违反）")
        if boundary_nonempty:
            witness_seen = True

    for rnd in rounds:
        for seq, slots in by_round[rnd]:
            joined = json.dumps(slots, ensure_ascii=False)
            for bad_code in CONTROL_CODES:
                if bad_code in joined:
                    v.add("A07-purity-r%d-s%s" % (rnd, seq), "FAIL",
                          "槽混入控制拒绝码 %s" % bad_code)
    v.add("A07-purity", "PASS" if not v.failed else "FAIL",
          "控制拒绝码零混入扫描完成")

    status, code = v.final(witness_seen)
    config_digest = hashlib.sha256(
        "|".join(sorted(d for d in digests if d)).encode("utf-8")).hexdigest()
    result = {
        "suiteVersion": SUITE_VERSION,
        "runId": run_id or ("offline:" + os.path.basename(envelope or "")),
        "status": status,
        "assertions": v.assertions,
        "coverage": {
            "rounds": rounds,
            "boundaries": [[r0, r1] for r0, r1 in zip(rounds, rounds[1:])],
            "nonEmptyWitness": witness_seen,
            "semanticNote": "witness=false 时结构过但语义覆盖不足（INCONCLUSIVE）",
        },
        "configDigest": config_digest,
        "evidenceRefs": [os.path.join(out_dir, "verify2-result.json")],
    }
    with open(os.path.join(out_dir, "verify2-result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print("ASSERT-OVERALL: %s (exit=%d) witness=%s rounds=%s"
          % (status, code, witness_seen, rounds))
    for a in v.assertions:
        if a["status"] in ("FAIL", "ERROR"):
            print("  [%s] %s: %s" % (a["status"], a["id"], a["detail"]))
    return code


if __name__ == "__main__":
    sys.exit(main())
