#!/bin/sh
cd /opt/build/pr || exit 9
echo '--- marker1 scorer evidence field (expect >=1):'
grep -c 'private final EvidenceRepository evidence;' control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java
echo '--- marker2 scorer invocation fallback (expect 0):'
grep -c 'rca_tool_invocation WHERE run_id' control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java
echo '--- marker3 config evidence bean (expect >=1):'
grep -c 'PostgresEvidenceRepository' control-app/src/main/java/com/objwww/pr/control/eval/application/EvalRunnerConfig.java
echo '--- marker4 migrations V145/V152:'
ls control-app/src/main/resources/db/migration/ | grep -E 'V14[56]__|V152__'
echo '--- workers:'
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'eval-worker|worker'
echo '--- eval_run states:'
PG=$(docker ps --format '{{.Names}}' | grep -E 'postgres|pg' | head -1)
docker exec -i "$PG" su - postgres -c "psql -d pr_agent -t -A -c 'select state||chr(58)||count(*) from eval_run group by state order by 1'"
echo '--- disk:'
df -h / | tail -1
