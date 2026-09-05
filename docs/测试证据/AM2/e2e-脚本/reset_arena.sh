#!/bin/sh
set -e
echo "== reset arena tables =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "TRUNCATE arena.oa_resource_ledger, arena.oa_payment_record, arena.oa_fulfillment_order, arena.oa_refund_order, arena.oa_idempotency_record, arena.oa_trade_order, arena.oa_compensation_outbox, arena.oa_probe_finding, arena.oa_injection_audit, arena.oa_scenario_map, arena.oa_chaos_event, arena.oa_chaos_session, arena.ground_truth_scenario RESTART IDENTITY CASCADE"
echo RESET_OK
