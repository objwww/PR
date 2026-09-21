# -*- coding: utf-8 -*-
"""8766 端口重启验证：rounds=2 快速跑一抡确认新代码可用。"""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8766"
DS = json.loads(open("example-dataset.json", encoding="utf-8").read())


def call(m, p, payload=None, token=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["X-Lab-Token"] = token
    req = urllib.request.Request(BASE + p, method=m,
                                 data=json.dumps(payload).encode() if payload is not None else None,
                                 headers=h)
    with urllib.request.urlopen(req, timeout=60) as r:
        b = r.read().decode()
        return json.loads(b) if b.strip() else None


t = call("GET", "/api/config")["token"]
rid = call("POST", "/api/runs", {"mode": "demo", "dataset": DS, "options": {"rounds": 2}}, t)["id"]
for _ in range(60):
    run = call("GET", "/api/runs/" + rid)
    if run["status"] not in ("RUNNING", "CANCELLING"):
        break
    time.sleep(1)
print("8766 rounds=2 status:", run["status"],
      "| valid_pairs:", (run.get("summary") or {}).get("valid_pairs"),
      "| verdict:", (run.get("summary") or {}).get("verdict"))
