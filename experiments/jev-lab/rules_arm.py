# -*- coding: utf-8 -*-
"""规则臂（JE-B01）：零模型费用的确定性证据选材基线。

回答的问题：不调用 Jev，仅靠规则（必保留钉窗、去重、错误关键词、预算截断）
能从同一冻结证据池选回多少 gold 相关证据？它是下一版实验（A=窗口 / R=规则 /
J=Jev）中证明"Jev 本身是否有增量"的必要对照——没有 R 臂，J 对 A 的提升说不清
是 Jev 的功劳还是任何选材都能拿到的功劳。

只做确定性变换：不调用任何模型、不修改语义、恢复原始顺序、必保留证据恒入选。
与 lab.py 的契约对齐：select_evidence 同形的 (case, opts) → 证据列表；必保留证据
超预算在任何 API 之前拒绝（same as lab 纪律）。禁止用于线上裁剪——它只是实验基线。
"""

import re

_KEYWORDS = (
    "error", "fatal", "exception", "panic", "critical", "timeout", "refused",
    "failed", "failure", "denied", "5xx", "500", "502", "503",
    "异常", "错误", "失败", "超时", "拒绝", "宕", "崩溃",
)

_DEDIGUP = (
    (re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<uuid>"),
    (re.compile(r"\b[0-9a-f]{12,}\b"), "<hex>"),
    (re.compile(r"\d+"), "<n>"),
)


def _normalize(text):
    """行模板归一（与 ContextAssembler.logSignature 同思路）：uuid/hex/数字归位。"""
    lowered = (text or "").lower()
    for pattern, replacement in _DEDIGUP:
        lowered = pattern.sub(replacement, lowered)
    return lowered.strip()


def _is_relevant(text):
    lowered = (text or "").lower()
    return any(keyword in lowered for keyword in _KEYWORDS)


def select_rules(case, opts):
    """规则选材：必保留钉窗 → 去重（同模板保首条）→ 关键词优先 → 原序补足。

    返回与 lab.select_evidence 同形的列表（每项 = 原证据 dict，恢复原始顺序）。
    必保留证据总字符超预算 → ValueError（调用方在任何 API 之前拒绝）。
    """
    max_items = int(opts.get("max_items", 20))
    max_chars = int(opts.get("max_chars", 8000))
    evidence = case["evidence"]
    required_ids = {e["id"] for e in evidence if e.get("required")}

    required_chars = sum(len(e["text"]) for e in evidence if e.get("required"))
    if required_chars > max_chars:
        raise ValueError("必保留证据超预算（%d > %d 字符），在任何 API 之前拒绝"
                         % (required_chars, max_chars))

    selected_ids = []
    seen_templates = set()

    # ① 必保留恒入选（保原序）
    for e in evidence:
        if e.get("required") and e["id"] not in selected_ids:
            selected_ids.append(e["id"])
            seen_templates.add(_normalize(e["text"]))

    # ② 去重 + 关键词优先（同模板只保首条；无关键词命中时 ② 不占预算）
    for e in evidence:
        if len(selected_ids) >= max_items:
            break
        template = _normalize(e["text"])
        if template in seen_templates:
            continue
        if _is_relevant(e["text"]):
            selected_ids.append(e["id"])
            seen_templates.add(template)

    # ③ 原序补足剩余预算（选材不引入阅读顺序变化——与 lab Jev 臂同律）
    for e in evidence:
        if len(selected_ids) >= max_items:
            break
        template = _normalize(e["text"])
        if template in seen_templates:
            continue
        selected_ids.append(e["id"])
        seen_templates.add(template)

    # ④ 字符预算截断（保留项原序累计，超预算丢尾并保持 ≤ max_chars）
    chosen, used = [], 0
    selected_set = set(selected_ids)
    for e in evidence:
        if e["id"] not in selected_set:
            continue
        if used + len(e["text"]) > max_chars and e["id"] not in required_ids:
            continue
        chosen.append(e)
        used += len(e["text"])
    return chosen
