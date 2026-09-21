#!/bin/sh
# WC-5 真机指标核验：operator bearer 只在本机使用，不回显 token
cd /opt/build/pr/deploy
TOK=$(grep -E '^APP_OPERATOR_API_BEARER=' .env | head -1 | cut -d= -f2- | tr -d '"' )
if [ -z "$TOK" ]; then echo "no_operator_bearer_in_.env"; exit 0; fi
echo "--- /actuator/metrics list status ---"
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOK" http://127.0.0.1:8080/actuator/metrics
for m in rca_reconcile_last_success_epoch_ms rca_reconcile_oldest_unseen_age_ms \
         rca_reconcile_terminal_run_open_tasks rca_reconcile_scan_duration \
         rca_reconcile_decision_total rca_reconcile_unknown_action_total \
         rca_late_commit_rejected_total rca_cancel_to_quiesce; do
  out=$(curl -s -H "Authorization: Bearer $TOK" "http://127.0.0.1:8080/actuator/metrics/$m")
  echo "$m => $(echo "$out" | head -c 300)"
done
exit 0
