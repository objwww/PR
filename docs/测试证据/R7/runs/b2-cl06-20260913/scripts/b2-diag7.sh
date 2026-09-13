#!/bin/sh
RID=$(cat /tmp/b2cl06/run-id.txt | sed 's/run=//')
echo "run=$RID"
echo "== checkpoint anchor facts（rev|phase|steps|mem_rev|mem_digest==cp_digest）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT cp.revision, cp.phase, cp.steps_used, m.checkpoint_revision, (m.memory_digest=cp.memory_digest)
FROM rca_primary_checkpoint cp JOIN rca_working_memory m ON cp.memory_id=m.id
WHERE cp.run_id='$RID'"
echo "== 全部记忆行（rev|digest12）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT checkpoint_revision, left(memory_digest,12) FROM rca_working_memory
WHERE run_id='$RID' ORDER BY checkpoint_revision"
echo "== 候选新断言（锚定最新行+digest）：期望 0 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "
SELECT count(*) FROM rca_primary_checkpoint cp
JOIN rca_working_memory m ON cp.memory_id=m.id
WHERE cp.run_id='$RID'
  AND (m.checkpoint_revision <> (SELECT max(checkpoint_revision) FROM rca_working_memory WHERE run_id='$RID')
       OR m.memory_digest <> cp.memory_digest)"
