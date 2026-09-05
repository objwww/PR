#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AM2 M2-25~28 三场景 E2E 驱动（195 真栈，slot=1 串行）。

用法（在 arena-e2e-cli 容器内，双网 eval-mgmt+alert-net）：
  python3 /e2e/driver.py preflight
  python3 /e2e/driver.py phase1 F1|F2|F3
  python3 /e2e/driver.py phase2 F1|F2|F3 <incidentId> <incidentGeneration> <runId> <reportId>
  python3 /e2e/driver.py ttl
  python3 /e2e/driver.py dpc02

指纹对账三重一致（C-6）：激活响应指纹 == Alertmanager 观测指纹 == 本驱动按最终标签集重算。
输出行协议：E2E|PASS/FAIL/INFO|检查名|详情；退出码 = FAIL 数。
"""
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ADMIN = "http://arena-chaos-admin:8080"
ARENA = "http://order-arena:8080"
PROM = "http://prometheus-am0:9090"
AM = "http://alertmanager-am0:9093"

# C-6 冻结常量（最终标签集 = alertname/severity/service/fault_type/job/instance）。
# 2026-09-05 以 195 真栈 ground truth 反推锁定算法变体：每对 name ff value ff
# （尾随 0xff 含末对，ByteSerialize 形态）——F1 值与 AM API 实测一致。
FROZEN_FP = {
    "F1": "0d7404ae811ae84a",   # ArenaDuplicateOrders
    "F2": "653693464eea7e9b",   # ArenaIllegalTransitions
    "F3": "f95e79c26f0e7b4c",   # ArenaOrderStuck
}
ALERTNAME = {"F1": "ArenaDuplicateOrders", "F2": "ArenaIllegalTransitions",
             "F3": "ArenaOrderStuck"}
BASE_LABELS = {"severity": "page", "service": "order-arena",
               "job": "order-arena", "instance": "order-arena:8080"}


def load_token():
    with open("/e2e/env") as f:
        for line in f:
            if line.startswith("CHAOS_ADMIN_TOKEN="):
                return line.strip().split("=", 1)[1]
    raise SystemExit("env 文件缺 CHAOS_ADMIN_TOKEN")


TOKEN = load_token()


def load_tag():
    """同轮 run 的 phase1/phase2 是不同进程——orchestrator 先把 tag 写入 /e2e/tag；
    scenario_id 全局唯一（uq_chaos_scenario），重跑必须换名，全部资源名带 tag。"""
    try:
        with open("/e2e/tag") as f:
            t = f.read().strip()
            if t:
                return t
    except Exception:
        pass
    return time.strftime("%m%d%H%M%S", time.gmtime())


TAG = load_tag()


def tagged(base):
    return "%s-%s" % (base, TAG)

FAILS = []


def check(name, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    if not cond:
        FAILS.append(name)
    print("E2E|%s|%s|%s" % (tag, name, detail), flush=True)
    return cond


def info(name, detail):
    print("E2E|INFO|%s|%s" % (name, detail), flush=True)


def req(method, url, body=None, token=None, timeout=20):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Admin-Token"] = token
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw) if raw else {}
            except Exception:
                return resp.status, {"text": raw.decode(errors="replace")}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else {}
        except Exception:
            return e.code, {"raw": raw.decode(errors="replace")}
    except Exception as e:
        return -1, {"error": repr(e)}


def wait(fn, timeout, interval=5):
    """fn() -> (ok, value)；ok 为真返回 value，超时返回 None"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        ok, value = fn()
        if ok:
            return value
        time.sleep(interval)
    return None


def c6_fingerprint(labels):
    """C-6：FNV-1a 64（offset 0xcbf29ce484222325, prime 0x100000001b3），
    标签按名字典序，每对 name → 0xff → value → 0xff（尾随分隔含末对，
    prometheus/common ByteSerialize 形态，与 AM 实测一致）。"""
    h = 0xcbf29ce484222325

    def add(b):
        nonlocal h
        h = ((h ^ b) * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF

    for k in sorted(labels):
        for b in k.encode():
            add(b)
        add(0xFF)
        for b in str(labels[k]).encode():
            add(b)
        add(0xFF)
    return format(h, "016x")


def hex64(s):
    return hashlib.sha256(s.encode()).hexdigest()


# ---------------- 客户端 ----------------

def prom_alerts():
    st, body = req("GET", PROM + "/api/v1/alerts")
    return body.get("data", {}).get("alerts", []) if st == 200 else []


def prom_firing(alertname):
    for a in prom_alerts():
        if a.get("labels", {}).get("alertname") == alertname \
                and a.get("state") in ("firing", "active"):
            return a
    return None


def prom_query(expr):
    st, body = req("GET", PROM + "/api/v1/query?" +
                   urllib.parse.urlencode({"query": expr}))
    if st != 200:
        return None
    res = body.get("data", {}).get("result", [])
    return float(res[0]["value"][1]) if res else None


def am_alert(alertname):
    st, body = req("GET", AM + "/api/v2/alerts")
    if st != 200:
        return None
    for a in body:
        if a.get("labels", {}).get("alertname") == alertname \
                and a.get("status", {}).get("state") in ("active", "suppressed"):
            return a
    return None


def create_order(intent, corr, sku, amount="10.00"):
    return req("POST", ARENA + "/orders", {
        "intentId": intent, "correlationId": corr, "buyerId": "e2e-buyer",
        "sku": sku, "quantity": 1, "amount": amount})


def get_order(oid):
    return req("GET", ARENA + "/orders/" + oid)


def activate(fault, scenario, target, labels, ttl=900):
    body = {
        "scenarioId": scenario, "target": target, "ttlSeconds": ttl,
        "operator": "e2e-cli@" + TAG,
        "configDigest": hex64("cfg|%s|%s" % (fault, scenario)),
        "ruleDigest": hex64("rule|%s|%s" % (fault, scenario)),
        "groundTruth": {
            "schemaVersion": 1,
            "datasetVersion": "gt-%s-%s" % (scenario, TAG),
            "payloadDigest": hex64("gt|%s|%s" % (scenario, TAG)),
            "applicableScope": "order-arena"},
        "alertLabels": labels,
    }
    result = req("POST", ADMIN + "/chaos/%s/on" % fault, body, token=TOKEN)
    if result[0] == 201:
        # 开关读面 = 2s 可丢缓存（流量持续刷新它）：激活后必须等快照过期，
        # 注入流量才能读到新会话——否则 F1 旁路/F3 UNKNOWN 都读旧空快照
        time.sleep(3)
    return result


def deactivate(fault, scenario, gen):
    return req("POST", ADMIN + "/chaos/%s/off" % fault,
               {"scenarioId": scenario, "expectedGeneration": gen}, token=TOKEN)


def status(scenario):
    return req("GET", ADMIN + "/chaos/status?" +
               urllib.parse.urlencode({"scenarioId": scenario}), token=TOKEN)


def backfill(scenario, incident_id, incident_generation, run_id, report_id):
    return req("POST", ADMIN + "/chaos/scenario-map/backfill", {
        "scenarioId": scenario, "incidentId": incident_id,
        "incidentGeneration": incident_generation,
        "runId": run_id, "reportId": report_id}, token=TOKEN)


def session_state(scenario):
    st, body = status(scenario)
    if st != 200:
        return None, {}
    return body.get("session", {}).get("state"), body


# ---------------- 场景参数 ----------------

class Scen:
    def __init__(self, fault, scenario, target):
        self.fault = fault
        self.scenario = scenario
        self.target = target
        self.alertname = ALERTNAME[fault]
        self.labels = dict(BASE_LABELS, alertname=self.alertname,
                           fault_type=fault)
        self.fp = FROZEN_FP[fault]


SC = {
    "F1": Scen("F1", tagged("f1-e2e"), tagged("chaos-f1e2e")),
    "F2": Scen("F2", tagged("f2-e2e"), tagged("chaos-f2e2e")),
    "F3": Scen("F3", tagged("f3-e2e"), tagged("chaos-f3e2e")),
}


# ---------------- preflight ----------------

def preflight():
    check("pf:arena healthz", req("GET", ARENA + "/healthz")[0] == 200)
    check("pf:admin healthz", req("GET", ADMIN + "/healthz")[0] == 200)
    st, body = req("POST", ADMIN + "/chaos/F1/on", {"scenarioId": "x"})
    check("pf:admin 无 token → 401", st == 401, str(st))
    st, body = req("POST", ADMIN + "/chaos/F1/on", {"scenarioId": "x"},
                   token="wrong-token-0000")
    check("pf:admin 错 token → 401", st == 401, str(st))
    st, body = req("GET", PROM + "/-/healthy")
    check("pf:prometheus healthy", st == 200, str(st))
    st, body = req("GET", AM + "/api/v2/status")
    check("pf:alertmanager 可达", st == 200, str(st))
    up = prom_query("oa_domain_probe_up")
    check("pf:oa 指标在位", up == 1.0, "oa_domain_probe_up=%s" % up)
    info("preflight", "done tag=%s" % TAG)


# ---------------- 告警观测 ----------------

def wait_alert(sc):
    def probe():
        a = prom_firing(sc.alertname)
        return a is not None, a
    a = wait(probe, 240)
    if a is None:
        check("p1:%s 告警 firing" % sc.fault, False,
              "240s 内未观测到 %s" % sc.alertname)
        return False
    info("p1:alert-json", json.dumps(a, ensure_ascii=False))
    check("p1:%s 标签集精确一致" % sc.fault, a.get("labels") == sc.labels,
          json.dumps(a.get("labels"), sort_keys=True))
    recomputed = c6_fingerprint(a.get("labels", {}))
    am = am_alert(sc.alertname)
    am_fp = am.get("fingerprint") if am else None
    check("p1:%s 指纹三重一致" % sc.fault,
          am_fp == sc.fp and recomputed == sc.fp,
          "am=%s recompute=%s frozen=%s" % (am_fp, recomputed, sc.fp))
    return am_fp == sc.fp and recomputed == sc.fp


# ---------------- 注入（业务操作面） ----------------

def save_ids(fault, ids):
    """phase1/phase2 是不同进程：订单号经容器内文件传递"""
    with open("/e2e/ids_%s" % fault, "w") as f:
        f.write(",".join(ids))


def load_ids(fault):
    with open("/e2e/ids_%s" % fault) as f:
        return [x for x in f.read().strip().split(",") if x]


def inject_f1(sc):
    ids = []
    for suffix in ("a", "b", "c"):
        st, body = create_order(tagged("e2e-f1-intent-1"),
                                "%s-%s" % (sc.target, suffix), "sku-std")
        ok = check("p1:F1 创单%s → 201 ENABLED（幂等旁路生效）" % suffix,
                   st == 201 and body.get("bookingStatus") == "ENABLED",
                   "%s %s" % (st, body))
        if not ok:
            return
        ids.append(body["orderId"])
    check("p1:F1 三单互异", len(set(ids)) == 3, str(ids))
    sc.order_ids = ids
    save_ids("F1", ids)


def inject_f2(sc):
    st, body = create_order(tagged("e2e-f2-intent-1"), sc.target + "-1",
                            "sku-std")
    if not check("p1:F2 创单 → 201 ENABLED", st == 201 and
                 body.get("bookingStatus") == "ENABLED", "%s %s" % (st, body)):
        return
    oid = body["orderId"]
    sc.order_ids = [oid]
    save_ids("F2", sc.order_ids)

    def gone():
        st2, b2 = get_order(oid)
        return st2 == 404, st2
    st3 = wait(gone, 90)
    check("p1:F2 注入回跳生效（GET 404 CREATED 不可见）", st3 == 404,
          "最终 GET=%s" % st3)


def inject_f3(sc):
    specs = [("1", "sku-std", tagged("e2e-f3-intent-1")),
             ("2", "sku-x-latesuccess", tagged("e2e-f3-intent-2"))]
    t0 = time.time()
    for suffix, sku, intent in specs:
        st, body = create_order(intent, sc.target + "-" + suffix, sku)
        ok = check("p1:F3 创单%s(%s) → 201 CREATED/UNKNOWN" % (suffix, sku),
                   st == 201 and body.get("bookingStatus") == "CREATED"
                   and body.get("paymentResult") == "UNKNOWN",
                   "%s %s" % (st, body))
        if not ok:
            return
        sc.__dict__.setdefault("order_ids", []).append(body["orderId"])
    save_ids("F3", sc.order_ids)
    # F3 窗口语义：ACTIVE 期间对账器不得提前收掉（新 SQL 排除 ACTIVE 靶面）。
    # 等 ≥45s（> unknown-older-than 10s + 多个对账周期）后订单必须仍 CREATED/404。
    while time.time() - t0 < 45:
        time.sleep(5)
    st, body = get_order(sc.order_ids[0])
    check("p1:F3 窗口保持（45s 后仍 CREATED 不可见）", st == 404,
          "GET=%s %s" % (st, body))
    check("p1:F3 窗口保持（次单同判）",
          get_order(sc.order_ids[1])[0] == 404, "")


INJECT = {"F1": inject_f1, "F2": inject_f2, "F3": inject_f3}


def phase1(fault):
    sc = SC[fault]
    local_fp = c6_fingerprint(sc.labels)
    check("p1:%s 本地重算==冻结常量" % fault, local_fp == sc.fp, local_fp)
    st, body = activate(fault, sc.scenario, sc.target, sc.labels)
    if not check("p1:%s 激活 201" % fault, st == 201, "%s %s" % (st, body)):
        sys.exit(1)
    info("p1:activation", json.dumps(body))
    check("p1:%s 激活响应指纹==冻结" % fault,
          body.get("alertFingerprint") == sc.fp, str(body))
    INJECT[fault](sc)
    wait_alert(sc)


# ---------------- 恢复断言（phase2） ----------------

def recovery_expect_f1(sc):
    a, b, c = sc.order_ids

    def done():
        ra, rb, rc = (get_order(x) for x in (a, b, c))
        if any(x[0] != 200 for x in (ra, rb, rc)):
            return False, None
        states = (ra[1]["bookingStatus"], rb[1]["bookingStatus"],
                  rc[1]["bookingStatus"])
        return states == ("ENABLED", "DISCARDED", "DISCARDED"), states
    states = wait(done, 180)
    check("p2:F1 canonical 保留 + 重复单废单", states is not None,
          "a/b/c=%s" % (states,))
    for oid in sc.order_ids:
        info("p2:order-view", json.dumps(get_order(oid)[1], ensure_ascii=False))


def recovery_expect_f2(sc):
    oid = sc.order_ids[0]

    def back():
        st, body = get_order(oid)
        return st == 200 and body.get("bookingStatus") == "ENABLED", (st, body)
    r = wait(back, 180)
    check("p2:F2 事实驱动恢复 → ENABLED", r is not None, str(r))
    if r:
        info("p2:order-view", json.dumps(r[1], ensure_ascii=False))


def recovery_expect_f3(sc):
    declined_id, late_id = sc.order_ids

    def done():
        r1, r2 = get_order(declined_id), get_order(late_id)
        if r1[0] != 200 or r2[0] != 200:
            return False, None
        states = (r1[1]["bookingStatus"], r2[1]["bookingStatus"])
        return states == ("DISCARDED", "ENABLED"), states
    states = wait(done, 180)
    check("p2:F3 拒绝裁定废单 + 迟到成功补 ENABLE", states is not None,
          "std/latesuccess=%s" % (states,))
    if states:
        info("p2:order-view[std]",
             json.dumps(get_order(declined_id)[1], ensure_ascii=False))
        info("p2:order-view[latesuccess]",
             json.dumps(get_order(late_id)[1], ensure_ascii=False))


RECOVERY = {"F1": recovery_expect_f1, "F2": recovery_expect_f2,
            "F3": recovery_expect_f3}


def phase2(fault, incident_id, incident_generation, run_id, report_id):
    sc = SC[fault]
    sc.order_ids = load_ids(fault)
    st, body = backfill(sc.scenario, incident_id, int(incident_generation),
                        run_id, report_id)
    check("p2:%s incident 回填 202 + v2 + 指纹一致" % fault,
          st == 202 and body.get("mappingVersion") == 2
          and body.get("alertFingerprint") == sc.fp,
          "%s %s" % (st, body))
    st, body = deactivate(fault, sc.scenario, 0)
    check("p2:%s off → 202 RECOVERING" % fault,
          st == 202 and body.get("state") == "RECOVERING", "%s %s" % (st, body))

    RECOVERY[fault](sc)

    def recovered_audit():
        _, sbody = status(sc.scenario)
        return "RECOVERED" in json.dumps(sbody.get("audit", [])), sbody.get("audit")
    audit = wait(recovered_audit, 60)
    check("p2:%s 审计含会话级 RECOVERED" % fault, audit is not None,
          json.dumps(audit, ensure_ascii=False)[:400])

    def closed():
        state, _ = session_state(sc.scenario)
        return state == "CLOSED", state
    state = wait(closed, 90)
    check("p2:%s 会话 CLOSED（管理面依审计推进）" % fault, state == "CLOSED",
          "state=%s" % state)

    def down():
        return (prom_firing(sc.alertname) is None
                and (prom_query("oa_%s" % sc.gauge()) or 0) == 0.0), None
    wait(down, 180)
    check("p2:%s 告警回落 + 台账归零" % fault,
          prom_firing(sc.alertname) is None
          and (prom_query("oa_%s" % sc.gauge()) or 0) == 0.0,
          "gauge=%s" % prom_query("oa_%s" % sc.gauge()))
    info("p2:final-status", json.dumps(status(sc.scenario)[1], ensure_ascii=False))


def gauge_name(self):
    return {"F1": "oa_duplicate_orders_current",
            "F2": "oa_state_violations_current",
            "F3": "oa_stuck_orders_current"}[self.fault]


Scen.gauge = gauge_name


# ---------------- TTL 自愈演练 ----------------

def ttl():
    sc = Scen("F1", tagged("f1-ttl"), tagged("chaos-ttl"))
    st, body = activate("F1", sc.scenario, sc.target, sc.labels, ttl=30)
    if not check("ttl:激活 201（ttl=30s）", st == 201, "%s %s" % (st, body)):
        sys.exit(1)
    st1, b1 = create_order(tagged("e2e-ttl-intent-1"), sc.target + "-a", "sku-std")
    st2, b2 = create_order(tagged("e2e-ttl-intent-1"), sc.target + "-b", "sku-std")
    check("ttl:重复单就位", st1 == 201 and st2 == 201
          and b1.get("bookingStatus") == "ENABLED"
          and b2.get("bookingStatus") == "ENABLED", "%s/%s" % (b1, b2))
    sc.order_ids = [b1.get("orderId"), b2.get("orderId")]

    def closed_with_recovery():
        state, sbody = session_state(sc.scenario)
        return (state == "CLOSED"
                and "RECOVERED" in json.dumps(sbody.get("audit", []))), state
    state = wait(closed_with_recovery, 300)
    check("ttl:无操作员自愈 TTL→RECOVERING→CLOSED+RECOVERED", state == "CLOSED",
          "state=%s" % state)
    _, sbody = status(sc.scenario)
    info("ttl:final-status", json.dumps(sbody, ensure_ascii=False))

    def biz_done():
        r1, r2 = get_order(sc.order_ids[0]), get_order(sc.order_ids[1])
        if r1[0] != 200 or r2[0] != 200:
            return False, None
        states = (r1[1]["bookingStatus"], r2[1]["bookingStatus"])
        return states == ("ENABLED", "DISCARDED"), states
    biz = wait(biz_done, 180)
    check("ttl:恢复语义仍成立（canonical 保留）", biz is not None, str(biz))

    def down():
        return (prom_firing(sc.alertname) is None
                and prom_query("oa_duplicate_orders_current") == 0.0), None
    wait(down, 180)
    check("ttl:告警回落 + 台账归零",
          prom_firing(sc.alertname) is None
          and prom_query("oa_duplicate_orders_current") == 0.0,
          "gauge=%s" % prom_query("oa_duplicate_orders_current"))


# ---------------- DP-C02 正常流量 ----------------

def dpc02():
    v0 = prom_query("arena_traffic_submitted_total")
    time.sleep(70)
    v1 = prom_query("arena_traffic_submitted_total")
    inc = prom_query("increase(arena_traffic_submitted_total[2m])")
    check("dpc02:流量计数持续增长", v0 is not None and v1 is not None
          and v1 > v0 and (inc or 0) > 0, "%s→%s inc=%s" % (v0, v1, inc))
    st, body = create_order(tagged("e2e-live-intent-1"), "live-e2e-" + TAG,
                            "sku-std")
    ok = check("dpc02:live- 创单 → 201 ENABLED",
               st == 201 and body.get("bookingStatus") == "ENABLED",
               "%s %s" % (st, body))
    if ok:
        st2, b2 = get_order(body["orderId"])
        check("dpc02:live- GET 可见", st2 == 200
              and b2.get("correlationId", "").startswith("live-"),
              json.dumps(b2, ensure_ascii=False))
    st3, b3 = create_order(tagged("e2e-live-intent-1"),
                           "live-e2e-replay-" + TAG, "sku-std")
    # 幂等契约：requestDigest=sha256(intentId|buyer|sku|qty|amount)，不含 correlationId
    # ——同 intent 同载荷重放 → 200 replayed=true 返回原单（live- 流量不跳幂等，INV-AM2-1）
    check("dpc02:live- 幂等重放返回原单（200 replayed）",
          st3 == 200 and b3.get("replayed") is True
          and b3.get("orderId") == body.get("orderId"),
          "%s %s" % (st3, b3))
    check("dpc02:探针自证 up", prom_query("oa_domain_probe_up") == 1.0,
          "probe_up=%s" % prom_query("oa_domain_probe_up"))


# ---------------- main ----------------

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "preflight":
        preflight()
    elif cmd == "phase1":
        phase1(sys.argv[2].upper())
    elif cmd == "phase2":
        phase2(sys.argv[2].upper(), *sys.argv[3:7])
    elif cmd == "ttl":
        ttl()
    elif cmd == "dpc02":
        dpc02()
    else:
        print("unknown command: %s" % cmd)
        sys.exit(2)
    print("E2E|SUBTOTAL|%s|fail=%d" % (cmd, len(FAILS)), flush=True)
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
