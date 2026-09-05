#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P4 实验统计：读 raw/*.jsonl，按 模型×臂 汇总输出统计表（stats.md）。

用法：
  python analyze_p4.py            # 汇总 → stats.md
  python analyze_p4.py --detail   # 另打印逐条明细（模型 臂 序号 形态 提取路径 schema判定）
"""
import io
import json
import sys
from collections import Counter
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"

MODELS = ["deepseek-v3", "qwen-max", "qwen-plus"]
ARMS = ["instr", "schema", "jsonobj"]
ARM_LABEL = {
    "instr": "A 文字硬指令(BA-14修复)",
    "schema": "B response_format strict json_schema",
    "jsonobj": "C response_format json_object",
}


def load_all():
    rows = []
    for f in sorted(RAW.glob("*.jsonl")):
        for line in io.open(f, encoding="utf-8"):
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def pct(x, n):
    return ("%5.1f%%" % (100.0 * x / n)) if n else "  n/a"


def main():
    detail = "--detail" in sys.argv
    rows = load_all()
    if detail:
        for r in rows:
            print("%-12s %-8s #%-2d http=%-4s shape=%-9s path=%-14s ok=%-5s missing=%-22s refviol=%s" % (
                r["model"], r["arm"], r["idx"], r["http_status"], r["shape"] or "-", r["path"] or "-",
                r["schema_ok"], ",".join(r["missing"] or []) or "-", r["ref_viol"]))
        print()

    out = []
    out.append("# P4 结构化输出稳定性——统计表\n")
    out.append("生成: analyze_p4.py 对 raw/*.jsonl 的机械汇总；判定链复刻 EvidencePackageValidator.parseAnalysis"
               "（整包→```json 围栏→围栏非法即拒→无围栏首'{'到末'}'片段）。\n")
    out.append("定义：**兜住率** = 「生产提取链成功取出 JSON 且七键 schema 完整」的响应占比；"
               "**schema 字段完整率**同口径（提取失败视作字段不完整，因为生产不会采纳该响应）。"
               "解析获救率 = 仅提取成功（不论 schema）。\n")

    head = ("| 模型 | 臂 | n | 纯JSON | 围栏包裹 | 散文 | 空/调用失败 | "
            "提取路径 direct/fence/braces/拒 | schema字段完整 | 解析获救 | artifact_ref违规 | 平均延迟ms | 平均总tokens |")
    sep = "|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    out += [head, sep]
    for m in MODELS:
        for a in ARMS:
            rs = [r for r in rows if r["model"] == m and r["arm"] == a]
            if not rs:
                continue
            n = len(rs)
            ok_http = [r for r in rs if r["http_status"] == 200 and r["shape"]]
            shape = Counter(r["shape"] for r in ok_http)
            path = Counter((r["path"] or "-") for r in ok_http)
            fail = n - len(ok_http)
            schema_ok = sum(1 for r in ok_http if r["schema_ok"])
            rescued = sum(1 for r in ok_http if r["path"] in ("direct", "fence", "braces"))
            refviol = sum(r["ref_viol"] or 0 for r in ok_http)
            lat = sorted(r["latency_ms"] for r in rs)[len(rs) // 2]
            toks = [ (r["usage"] or {}).get("total_tokens") for r in ok_http if (r["usage"] or {}).get("total_tokens")]
            lat_avg = int(sum(r["latency_ms"] for r in rs) / n)
            tok_avg = int(sum(toks) / len(toks)) if toks else 0
            out.append(
                "| %s | %s | %d | %s (%d) | %s (%d) | %s (%d) | %d | %d/%d/%d/%d | %s (%d) | %s (%d) | %d | %d | %d |" % (
                    m, ARM_LABEL[a], n,
                    pct(shape.get("pure_json", 0), len(ok_http)), shape.get("pure_json", 0),
                    pct(shape.get("fenced", 0), len(ok_http)), shape.get("fenced", 0),
                    pct(shape.get("prose", 0), len(ok_http)), shape.get("prose", 0),
                    fail,
                    path.get("direct", 0), path.get("fence", 0) + path.get("fence_invalid", 0),
                    path.get("braces", 0) + path.get("braces_invalid", 0),
                    path.get("fence_invalid", 0) + path.get("braces_invalid", 0) + path.get("no_candidate", 0),
                    pct(schema_ok, len(ok_http)), schema_ok,
                    pct(rescued, len(ok_http)), rescued,
                    refviol, lat_avg, tok_avg))
    out.append("")

    # missing-field 频次
    out.append("## schema 缺陷明细（提取成功但字段不完整的响应）\n")
    miss = Counter()
    refs = Counter()
    for r in rows:
        if r["http_status"] == 200 and r["shape"] and not r["schema_ok"]:
            for f in (r["missing"] or []):
                miss["%s/%s/%s" % (r["model"], r["arm"], f)] += 1
        if r["ref_viol"]:
            refs["%s/%s" % (r["model"], r["arm"])] += r["ref_viol"]
    if miss:
        for k, v in sorted(miss.items()):
            out.append("- `%s` × %d" % (k, v))
    else:
        out.append("- 无（提取成功的响应全部七键完整且类型合规）")
    if refs:
        out.append("\nartifact_ref 违规（非 prometheus://、dashboard:// 白名单）：")
        for k, v in sorted(refs.items()):
            out.append("- `%s` × %d" % (k, v))
    out.append("")

    (HERE / "stats.md").write_text("\n".join(out), encoding="utf-8")
    print("\n".join(out))


if __name__ == "__main__":
    main()
