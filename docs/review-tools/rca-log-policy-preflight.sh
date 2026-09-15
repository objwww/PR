#!/bin/sh
set -eu
. /opt/build/r7-operator-env.sh
docker exec deploy-postgres-1 psql "$R7_PG_URL" -X -v ON_ERROR_STOP=1 -P pager=off -c "
SELECT id,state,created_at FROM rca_run WHERE state IN ('QUEUED','RUNNING','REPORTING');
SELECT id,state,started_at FROM eval_run WHERE state='RUNNING';
SELECT id,status,generation FROM incident WHERE status='FIRING';
SELECT id,state,created_at,claimed_at FROM eval_run_command WHERE state IN ('PENDING','CLAIMED');
SELECT id,state,updated_at FROM drill_job WHERE state NOT IN ('CLOSED','FAILED','RECOVERY_FAILED');
SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='public'
AND table_name IN ('rca_evidence','rca_claim','rca_model_call','rca_run','drill_job','incident') ORDER BY table_name,ordinal_position;
"
docker inspect deploy-control-app-1 --format '{{json .Config.Env}}' | python3 -c '
import json,sys,hashlib
env=dict(x.split("=",1) for x in json.load(sys.stdin))
for k in ("APP_ALERT_R7_PRIMARY_PROMPT","APP_ALERT_R7_PRIMARY_TOOL_ALLOWLIST","APP_ALERT_AM4_LOGS_SERVICE_ALLOWLIST","APP_ALERT_R7_PRIMARY_MAX_STEPS","APP_ALERT_R7_PRIMARY_BUDGET_TOKENS","AGENT_MODEL","APP_EVAL_LAUNCH_ENABLED"):
    v=env.get(k,"<unset>")
    print(k+"="+v)
    if k.endswith("PROMPT"): print("PROMPT_SHA256="+hashlib.sha256(v.encode()).hexdigest())
'
docker inspect flagd --format '{{json .Mounts}}'
python3 -c 'import json; d=json.load(open("/opt/build/opentelemetry-demo/src/flagd/demo.flagd.json")); print("FLAG_VARIANTS="+json.dumps({k:v.get("defaultVariant") for k,v in d["flags"].items()},sort_keys=True))'
