# -*- coding: utf-8 -*-
"""30 批配对 DEMO 真实跑通验证：走 HTTP 接口（非单测直调）。
验证承诺面：30 批 × 案例配对、Recall/F1/CI、历史、逐案例审计。"""
import json
import time
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8767"
DS = json.loads(Path("example-dataset.json").read_text(encoding="utf-8"))


def call(method, path, payload=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Lab-Token"] = token
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(payload).encode() if payload is not None else None,
                                 headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read().decode("utf-8")
        return json.loads(body) if body.strip() else None


boot = call("GET", "/api/config")
TOKEN = boot["token"]
print("bootstrap ok, live_ready =", boot.get("live_ready"))

run_id = call("POST", "/api/runs", {"mode": "demo", "dataset": DS,
                                    "options": {"rounds": 30}}, TOKEN)["id"]
print("started:", run_id)
for _ in range(180):
    run = call("GET", "/api/runs/" + run_id)
    if run["status"] not in ("RUNNING", "CANCELLING"):
        break
    time.sleep(1)
print("status:", run["status"])
s = run.get("summary") or {}
print("planned/valid pairs:", s.get("planned_pairs"), "/", s.get("valid_pairs"))
print("verdict:", s.get("verdict"))
arms = s.get("arms") or {}
for name in ("baseline", "jev"):
    a = arms.get(name) or {}
    micro = a.get("micro") or {}
    print("  arm=%s micro_F1=%s recall=%s root_acc=%s sel_recall=%s tokens=%s"
          % (name, micro.get("f1"), micro.get("recall"), a.get("root_accuracy"),
             a.get("selection_recall"), a.get("total_tokens")))
print("paired_recall CI:", (s.get("paired_recall") or {}).get("ci95"))
print("paired_f1 CI:", (s.get("paired_f1") or {}).get("ci95"))
hist = call("GET", "/api/runs")
print("history rows:", len(hist))
p0 = (run.get("pairs") or [{}])[0]
print("per-case audit: batch=%s case=%s arms=%s baseline_calls=%s"
      % (p0.get("batch"), p0.get("case_id"),
         sorted(k for k in p0 if k in ("baseline", "jev")),
         len((p0.get("baseline") or {}).get("calls") or [])))
