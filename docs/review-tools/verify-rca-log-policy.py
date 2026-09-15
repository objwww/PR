"""Bounded live regression: preverify both sources, inject once, inspect, restore flags."""
import datetime
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
import urllib.parse
import uuid

OUT = Path(sys.argv[1])
POLICY_VERSION = sys.argv[2]
assert POLICY_VERSION in ("primary-log-investigation-v1", "primary-log-investigation-v2")
OUT.mkdir(parents=True, exist_ok=False)
FLAG_FILE = Path("/opt/build/opentelemetry-demo/src/flagd/demo.flagd.json")
COMMON = "/opt/build/pr/docs/测试证据/R7/e2e-脚本/e2e-r7-common.sh"
ALERT = "CheckoutRpcClientErrorRateHigh"


def command(args, stdin=None):
    result = subprocess.run(args, input=stdin, universal_newlines=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=40)
    if result.returncode:
        raise RuntimeError("Command failed: " + args[0] + ": " + result.stderr[-800:])
    return result.stdout


def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def sql(query):
    output = command(["docker", "exec", "-i", "deploy-postgres-1", "psql",
                      os.environ["R7_PG_URL"], "-XAt", "-v", "ON_ERROR_STOP=1"],
                     "SELECT COALESCE(json_agg(t),'[]'::json) FROM (" + query + ") t;")
    return json.loads(output)


def variants():
    return {k: v["defaultVariant"] for k, v in json.loads(FLAG_FILE.read_text())["flags"].items()}


def flag(name, value):
    result = command(["docker", "run", "--rm", "--network", "eval-mgmt",
                      "curlimages/curl:8.8.0", "-fsS", "-m", "8", "-X", "POST",
                      "http://flagd-admin:8081/flags", "-H", "content-type: application/json",
                      "-d", json.dumps({"flag": name, "variant": value})])
    assert variants()[name] == value, "Flag write not reflected in file"
    print("FLAG", name, value, flush=True)
    return result


def get(container, port, path, params):
    url = "http://localhost:" + str(port) + path + "?" + urllib.parse.urlencode(params)
    return json.loads(command(["docker", "exec", container, "wget", "-qO-", url]))


def inject(status):
    shell = ". /opt/build/r7-operator-env.sh; . " + shlex.quote(COMMON)
    shell += "; r7_inject_alert " + " ".join(map(shlex.quote, [
        ALERT, "checkout", status, str(OUT), "log-policy-regression-" + OUT.name]))
    command(["sh", "-c", shell])


def main():
    baseline = variants()
    save("flags-before.json", baseline)
    assert baseline["paymentFailure"] == "off", "paymentFailure already owned by another run"
    assert not sql("SELECT id FROM eval_run_command WHERE state IN ('PENDING','CLAIMED')"), "Eval worker busy"
    assert not sql("SELECT id FROM drill_job WHERE state NOT IN ('CLOSED','FAILED','RECOVERY_FAILED')"), "Drill busy"
    assert not sql("SELECT id FROM rca_run WHERE state IN ('QUEUED','RUNNING')"), "RCA busy"
    for _ in range(20):
        incidents = sql("SELECT id,status,generation FROM incident WHERE incident_key LIKE 'alertname="
                        + ALERT + "|%' ORDER BY last_event_at DESC LIMIT 1")
        if not incidents or incidents[0]["status"] == "RESOLVED":
            break
        print("WAIT_EPISODE", incidents[0]["generation"], flush=True)
        time.sleep(15)
    else:
        raise RuntimeError("Regression incident did not resolve naturally within 300 seconds")
    assert variants() == baseline, "Flag baseline changed while waiting"
    injected = False
    changed = {}
    try:
        for name, value in (("productCatalogFailure", "off"), ("paymentFailure", "100%")):
            # Track before the call: a failed response can still mean the write took effect.
            changed[name] = value
            flag(name, value)
        fault_start = int(time.time())
        for _ in range(5):
            time.sleep(20)
            print("PREWARM", int(time.time()) - fault_start, flush=True)
        now = int(time.time())
        window = now - fault_start
        query = ('sum by (rpc_method,rpc_response_status_code) (increase('
                 'rpc_client_call_duration_seconds_count{service_name="checkout",'
                 'rpc_response_status_code!="OK"}[' + str(window) + 's]))')
        metrics = get("prometheus-am0", 9090, "/api/v1/query", {"query": query, "time": now})
        logs = get("deploy-loki-1", 3100, "/loki/api/v1/query_range", {
            "query": '{service_name="payment"}', "start": str(fault_start) + "000000000",
            "end": str(now) + "000000000", "limit": 200, "direction": "backward"})
        save("preverified-metrics.json", metrics)
        save("preverified-payment-logs.json", logs)
        metric_hits = [r for r in metrics["data"]["result"]
                       if "PaymentService" in r["metric"].get("rpc_method", "") and float(r["value"][1]) > 0]
        log_hits = [v for stream in logs["data"]["result"] for v in stream["values"]
                    if "Payment request failed. Invalid token." in v[1]]
        assert metric_hits and log_hits, "Dual-source preverification failed; no alert injected"
        print("PREVERIFIED", len(metric_hits), "metric series", len(log_hits), "failure lines", flush=True)
        injected_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
        injected = True
        inject("firing")
        run = None
        for _ in range(60):
            rows = sql("SELECT r.id,r.state,r.generation,r.finished_at FROM rca_run r JOIN incident i ON i.id=r.incident_id "
                       "WHERE i.incident_key LIKE 'alertname=" + ALERT + "|%' AND r.created_at>='"
                       + injected_at + "' ORDER BY r.created_at DESC LIMIT 1")
            if rows and rows[0]["finished_at"]:
                run = rows[0]
                break
            time.sleep(2)
        assert run, "RCA did not reach terminal state within 120 seconds"
        run_id = str(uuid.UUID(run["id"]))
        save("run.json", run)
        evidence = sql("SELECT id,evidence_type,source,scope,time_start,time_end,payload FROM rca_evidence WHERE run_id='" + run_id + "' ORDER BY created_at")
        claims = sql("SELECT id,kind,claim_key,status,evidence_basis,lifecycle,reason,scope,sources,evidence_refs FROM rca_claim WHERE run_id='" + run_id + "'")
        calls = sql("SELECT state,requested_model,prompt_digest,usage,error_code FROM rca_model_call WHERE run_id='" + run_id + "' ORDER BY created_at")
        inputs = sql("SELECT m.id,m.role_digest, i.capture_level, position('" + POLICY_VERSION + "' in i.prompt_text)>0 AS new_policy_visible "
                     "FROM rca_model_call m LEFT JOIN rca_model_input i ON i.model_call_id=m.id WHERE m.run_id='" + run_id + "'")
        checkpoint = sql("SELECT final_claims,final_missing_information FROM rca_primary_checkpoint WHERE run_id='" + run_id + "'")
        save("evidence.json", evidence)
        save("claims.json", claims)
        save("model-calls.json", calls)
        save("policy-capture.json", inputs)
        save("checkpoint.json", checkpoint)
        read_payment = [e for e in evidence if e["evidence_type"] == "logs.query"
                        and "Payment request failed. Invalid token." in json.dumps(e["payload"])]
        confirmed = [c for c in claims if c["kind"] == "ROOT_CAUSE" and c["status"] == "TRUE"
                     and c["evidence_basis"] == "MULTI_SOURCE_CONSISTENT" and c["lifecycle"] == "ACTIVE"]
        visible = [i["new_policy_visible"] for i in inputs if i["new_policy_visible"] is not None]
        result = {"run_id": run_id, "state": run["state"], "payment_failure_body_read": bool(read_payment),
                  "root_cause_true": bool(confirmed), "new_policy_visible": all(visible) if visible else None,
                  "policy_version": POLICY_VERSION, "text_capture_available": bool(visible),
                  "model_calls": len(calls), "preverified_metric_series": len(metric_hits), "preverified_failure_lines": len(log_hits)}
        save("result.json", result)
        print(json.dumps(result), flush=True)
        assert result["new_policy_visible"] is not False, "Captured prompt does not contain revised policy"
        assert read_payment, "RCA still did not read downstream failure body"
        assert confirmed, "Downstream evidence read but root cause not confirmed"
    finally:
        errors = []
        for name, value in reversed(list(changed.items())):
            try:
                current = variants()[name]
                if current not in (value, baseline[name]):
                    raise RuntimeError("Concurrent flag change: " + name)
                if current != baseline[name]:
                    flag(name, baseline[name])
            except Exception as error:
                errors.append(str(error))
        if injected:
            try:
                inject("resolved")
            except Exception as error:
                errors.append(str(error))
        save("cleanup.json", {"flags_after": variants(), "errors": errors})
        print("CLEANUP", json.dumps(errors), flush=True)
        if errors:
            raise RuntimeError("Cleanup needs attention: " + "; ".join(errors))


signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
main()
