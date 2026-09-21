#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent"
echo '--- safety rows raw ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select scenario_id, round_no from eval_case_safety where eval_run_id::text like '37ce6af6%';"
echo '--- OLD join shape ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select s.scenario_id, coalesce(dv.partition_class,'-') from eval_case_safety s join eval_run r on r.id = s.eval_run_id left join case_version cv on cv.case_key = s.scenario_id left join dataset_version dv on dv.id = cv.dataset_version_id and dv.version = r.dataset_version where s.eval_run_id::text like '37ce6af6%';"
echo '--- NEW join shape ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select s.scenario_id, coalesce(dv.partition_class,'-') from eval_case_safety s join eval_run r on r.id = s.eval_run_id left join dataset_version dv on dv.version = r.dataset_version left join case_version cv on cv.dataset_version_id = dv.id and cv.case_key = s.scenario_id where s.eval_run_id::text like '37ce6af6%';"
echo '--- dataset_version rt-v2 rows ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select id, name, version, partition_class from dataset_version where version in ('rt-v2','eval-ds-1');"
