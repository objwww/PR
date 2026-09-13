#!/bin/sh
RID=$(cat /tmp/b2cl06/run-id.txt | sed 's/run=//')
echo "run=$RID"
echo "== memory rows（rev|has_parent|id8|parent8|slots）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT checkpoint_revision, parent_memory_id IS NOT NULL, left(id::text,8),
       coalesce(left(parent_memory_id::text,8),'-'), memory_json::text
FROM rca_working_memory WHERE run_id='$RID' ORDER BY checkpoint_revision"
echo "== checkpoint rows（rev|phase|round|batches|steps|mem8）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT revision, phase, round_id, batches_used, steps_used, left(memory_id::text,8)
FROM rca_primary_checkpoint WHERE run_id='$RID' ORDER BY revision"
echo "== model calls（seq|route|decision|steps）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT action_seq, coalesce(route_id,'-'), coalesce(decision,'-'), coalesce(steps_used::text,'-')
FROM rca_model_call WHERE run_id='$RID' ORDER BY action_seq" 2>/dev/null || \
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT action_seq, coalesce(route_id,'-'), coalesce(state,'-')
FROM rca_model_call WHERE run_id='$RID' ORDER BY action_seq"
